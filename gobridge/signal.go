package wmbridge

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf16"

	"github.com/google/uuid"
	"github.com/rs/zerolog"
	"go.mau.fi/mautrix-signal/pkg/libsignalgo"
	"go.mau.fi/mautrix-signal/pkg/signalmeow"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/events"
	signalpb "go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/protobuf/svr2pb"
	sgstore "go.mau.fi/mautrix-signal/pkg/signalmeow/store"
	"go.mau.fi/mautrix-signal/pkg/signalmeow/types"
	"go.mau.fi/util/dbutil"
	"golang.org/x/crypto/hkdf"
	"google.golang.org/protobuf/proto"
)


type sgConn struct {
	mu        sync.Mutex
	client    *signalmeow.Client
	device    *sgstore.Device
	container *sgstore.Container
	sql      *sql.DB
	listener EventListener
	path     string
	state    string
	cancel   context.CancelFunc
	linkCancel context.CancelFunc
	known map[string]bool
}

var (
	sgMu   sync.Mutex
	sgSelf *sgConn
)

const SgIDPrefix = "sg:"

const (
	sgErrNotInitialised = "not_initialised"
	sgErrNoSession      = "no_session"
	sgErrCodeRejected   = "code_rejected"
	sgErrNotRegistered  = "not_registered"
	sgErrManifestLocked = "manifest_locked"
	sgErrStoreFailed    = "store_failed"
	sgErrNoBackup       = "no_backup"
	sgErrWrongPIN       = "wrong_pin"
)

func sgUpstream(err error) string { return "upstream:" + err.Error() }

const sgLookupFailed = "failed"

func sgRestoreError(err error) string {
	var re signalmeow.SVR2RestoreError
	if errors.As(err, &re) {
		switch re.Status {
		case svr2pb.RestoreResponse_PIN_MISMATCH:
			return fmt.Sprintf("%s:%d", sgErrWrongPIN, re.Tries)
		case svr2pb.RestoreResponse_MISSING:
			return sgErrNoBackup
		}
	}
	return sgUpstream(err)
}

func sgActive() (*sgConn, *signalmeow.Client, *sgstore.Device) {
	c := sgGet()
	if c == nil {
		return nil, nil, nil
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return c, c.client, c.device
}

func sgGet() *sgConn {
	sgMu.Lock()
	defer sgMu.Unlock()
	return sgSelf
}

func (c *sgConn) log(level int, msg string) {
	if c.listener != nil {
		c.listener.OnLog(level, "signal: "+msg)
	}
}

func (c *sgConn) setState(state string) {
	c.mu.Lock()
	if c.state == state {
		c.mu.Unlock()
		return
	}
	c.state = state
	c.mu.Unlock()
	if c.listener != nil {
		c.listener.OnStateChanged(state)
	}
}

type sgLogWriter struct{ c *sgConn }

func (w *sgLogWriter) Write(p []byte) (int, error) {
	w.c.log(LogDebug, string(p))
	return len(p), nil
}

func (c *sgConn) logger() zerolog.Logger {
	return zerolog.New(&sgLogWriter{c: c}).Level(zerolog.InfoLevel).With().Timestamp().Logger()
}

func SignalInit(dataDir string, listener EventListener) bool {
	if err := os.MkdirAll(dataDir, os.ModePerm); err != nil {
		listener.OnLog(LogError, "signal: mkdir error "+err.Error())
		return false
	}
	c := &sgConn{
		listener: listener,
		path:     dataDir,
		state:    "disconnected",
		known:    make(map[string]bool),
	}

	ctx := context.TODO()
	db, err := sql.Open("sqlite3", fmt.Sprintf("file:%s/signal.db?_foreign_keys=on", dataDir))
	if err != nil {
		c.log(LogError, "sqlite open error "+err.Error())
		return false
	}
	opened := false
	defer func() {
		if !opened {
			_ = db.Close()
		}
	}()

	rawDB, err := dbutil.NewWithDB(db, "sqlite3")
	if err != nil {
		c.log(LogError, "dbutil error "+err.Error())
		return false
	}
	c.sql = db
	container := sgstore.NewStore(rawDB, dbutil.ZeroLogger(c.logger()))
	if err := container.Upgrade(ctx); err != nil {
		c.log(LogError, "store upgrade error "+err.Error())
		return false
	}
	c.container = container

	devices, err := container.GetAllDevices(ctx)
	if err != nil {
		c.log(LogError, "get devices error "+err.Error())
		return false
	}
	opened = true
	if len(devices) > 0 {
		c.device = devices[0]
		c.client = signalmeow.NewClient(c.device, c.logger(), c.handleEvent)
	}

	sgMu.Lock()
	sgSelf = c
	sgMu.Unlock()
	return true
}

func SignalHasSession() bool {
	c := sgGet()
	if c == nil {
		return false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.device != nil
}

func SignalSelfID() string {
	c := sgGet()
	if c == nil {
		return ""
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.device == nil {
		return ""
	}
	return SgIDPrefix + c.device.ACI.String()
}

func SignalConnect() bool {
	c, client, _ := sgActive()
	if client == nil {
		return false
	}

	c.setState("connecting")
	c.mu.Lock()
	if c.cancel != nil {
		c.cancel()
		c.cancel = nil
	}
	c.mu.Unlock()
	ctx, cancel := context.WithCancel(context.Background())
	statusChan, err := client.StartReceiveLoops(ctx)
	if err != nil {
		cancel()
		c.log(LogError, "start receive loops error "+err.Error())
		c.setState("disconnected")
		return false
	}
	c.mu.Lock()
	c.cancel = cancel
	c.mu.Unlock()

	settled := make(chan bool, 1)
	settle := func(ok bool) {
		select {
		case settled <- ok:
		default:
		}
	}

	go func() {
		for status := range statusChan {
			switch status.Event {
			case signalmeow.SignalConnectionEventConnected:
				c.setState("connected")
				settle(true)
			case signalmeow.SignalConnectionEventDisconnected, signalmeow.SignalConnectionEventError:
				c.setState("disconnected")
			case signalmeow.SignalConnectionEventLoggedOut:
				c.log(LogWarning, "unlinked by the primary device")
				client.ClearKeysAndDisconnect(context.TODO())
				c.dropDeadDevice()
				c.setState("logged_out")
				settle(false)
			}
		}
		settle(false)
	}()

	go c.publishSelfContact()

	select {
	case ok := <-settled:
		return ok
	case <-time.After(30 * time.Second):
		return false
	}
}

func SignalLinkStart(deviceName string) {
	c := sgGet()
	if c == nil {
		return
	}
	c.mu.Lock()
	container := c.container
	if c.linkCancel != nil {
		c.linkCancel()
		c.linkCancel = nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	c.linkCancel = cancel
	c.mu.Unlock()
	if container == nil {
		c.log(LogError, "link: the store is not open")
		c.listener.OnPairError(sgErrNotInitialised)
		return
	}
	if deviceName == "" {
		deviceName = "UniChat"
	}
	go func() {
		defer cancel()
		for resp := range signalmeow.PerformProvisioning(ctx, container, deviceName, false) {
			switch resp.State {
			case signalmeow.StateProvisioningURLReceived:
				c.listener.OnQrCode(resp.ProvisioningURL)
			case signalmeow.StateProvisioningDataReceived:
				if resp.ProvisioningData == nil {
					continue
				}
				c.adoptLinkedDevice(ctx, resp.ProvisioningData.ACI)
			case signalmeow.StateProvisioningError:
				err := resp.Err
				if err == nil {
					err = errors.New("link failed")
				}
				c.log(LogError, "link: "+err.Error())
				c.listener.OnPairError(sgUpstream(err))
			}
		}
	}()
}

func SignalLinkStop() {
	c := sgGet()
	if c == nil {
		return
	}
	c.mu.Lock()
	cancel := c.linkCancel
	c.linkCancel = nil
	c.mu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (c *sgConn) adoptLinkedDevice(ctx context.Context, aci uuid.UUID) {
	c.mu.Lock()
	container := c.container
	c.mu.Unlock()
	if container == nil {
		return
	}
	device, err := container.DeviceByACI(ctx, aci)
	if err != nil || device == nil {
		c.log(LogError, "link: could not load the linked device")
		c.listener.OnPairError(sgErrNotInitialised)
		return
	}
	c.mu.Lock()
	c.device = device
	c.client = signalmeow.NewClient(device, c.logger(), c.handleEvent)
	c.mu.Unlock()
	c.log(LogInfo, "linked as device "+strconv.Itoa(device.DeviceID))
	c.setState("linked")
}

func SignalSyncContacts() bool {
	c, client, device := sgActive()
	if client == nil || device == nil {
		return false
	}
	if len(device.MasterKey) == 0 {
		c.log(LogWarning, "no account key, so the stored contact list cannot be read")
		return false
	}
	ctx := context.TODO()
	update, err := client.FetchStorage(ctx, device.MasterKey, 0, nil)
	if err != nil {
		c.log(LogWarning, "stored contact list not readable: "+err.Error())
		return false
	}
	if update == nil {
		c.log(LogInfo, "no stored contact list for this account")
		return false
	}
	if err := client.ProcessStorage(ctx, update); err != nil {
		c.log(LogWarning, "storage sync failed: "+err.Error())
		return false
	}
	c.publishSelfContact()
	c.listener.OnContactsSynced()
	return true
}

func SignalDisconnect() {
	c := sgGet()
	if c == nil {
		return
	}
	c.mu.Lock()
	cancel, client := c.cancel, c.client
	c.cancel = nil
	c.mu.Unlock()
	if client != nil {
		client.StopReceiveLoops()
	}
	if cancel != nil {
		cancel()
	}
	c.setState("disconnected")
}

func SignalLogout() {
	c := sgGet()
	if c == nil {
		return
	}
	ctx := context.TODO()
	c.mu.Lock()
	client, device, container := c.client, c.device, c.container
	c.mu.Unlock()
	if client != nil {
		client.ClearKeysAndDisconnect(ctx)
	}
	if device != nil && container != nil {
		if err := container.DeleteDevice(ctx, &device.DeviceData); err != nil {
			c.log(LogWarning, "failed to delete device row: "+err.Error())
		}
	}
	c.mu.Lock()
	c.device, c.client = nil, nil
	c.known = make(map[string]bool)
	handle := c.sql
	c.container, c.sql = nil, nil
	c.mu.Unlock()
	c.setState("logged_out")

	if handle != nil {
		if err := handle.Close(); err != nil {
			c.log(LogWarning, "failed to close signal store: "+err.Error())
		}
	}
	sgMu.Lock()
	if sgSelf == c {
		sgSelf = nil
	}
	sgMu.Unlock()
}

func SignalSendTextQuoted(chatId, msgId, text, styles, quotedId, quotedText, quotedSender string, preview *Preview) string {
	c, client, device := sgActive()
	if client == nil || device == nil {
		return ""
	}

	timestamp := sgTimestamp(msgId)
	ranges := sgStyleRanges(styles)
	dm := &signalpb.DataMessage{
		Body:       proto.String(text),
		Timestamp:  &timestamp,
		BodyRanges: ranges,
	}
	if quotedId != "" {
		if qts, err := strconv.ParseUint(quotedId, 10, 64); err == nil {
			dm.Quote = &signalpb.DataMessage_Quote{
				Id:        &qts,
				AuthorAci: proto.String(sgTargetAuthor(chatId, quotedSender)),
				Text:      proto.String(quotedText),
			}
		}
	}
	sgApplyPreview(c, client, dm, preview)
	msg := signalmeow.WrapDataMessage(dm)
	msgID := fmt.Sprintf("%d", timestamp)
	if err := sgSend(c, client, chatId, msg); err != nil {
		return sgFailSend(c, "send", chatId, msgID, err)
	}
	return msgID
}

var sgPreviewUploads sync.Map

func sgApplyPreview(c *sgConn, client *signalmeow.Client, dm *signalpb.DataMessage, p *Preview) {
	if p.empty() {
		return
	}
	preview := &signalpb.Preview{Url: proto.String(p.Url)}
	if p.Title != "" {
		preview.Title = proto.String(p.Title)
	}
	if p.Description != "" {
		preview.Description = proto.String(p.Description)
	}
	dm.Preview = []*signalpb.Preview{preview}
	if !p.hasImage() {
		return
	}
	key := sha256.Sum256(p.Image)
	if cached, ok := sgPreviewUploads.Load(key); ok {
		preview.Image = proto.Clone(cached.(*signalpb.AttachmentPointer)).(*signalpb.AttachmentPointer)
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), linkThumbUploadTimeout)
	defer cancel()
	ptr, err := client.UploadAttachment(ctx, p.Image)
	if err != nil {
		c.log(LogWarning, "link preview upload failed: "+err.Error())
		return
	}
	ptr.ContentType = proto.String("image/jpeg")
	ptr.Width = proto.Uint32(uint32(p.Width))
	ptr.Height = proto.Uint32(uint32(p.Height))
	sgPreviewUploads.Store(key, ptr)
	preview.Image = proto.Clone(ptr).(*signalpb.AttachmentPointer)
}

func sgFailSend(c *sgConn, what, chatId, msgID string, err error) string {
	c.log(LogError, what+" failed: "+err.Error())
	c.listener.OnMessageSendFailed(chatId, msgID)
	return ""
}

func sgSend(c *sgConn, client *signalmeow.Client, chatId string, msg *signalpb.Content) error {
	ctx := context.TODO()
	bare := sgBareID(chatId)
	if selfID := SignalSelfID(); selfID != "" && chatId == selfID {
		if err := client.SendNoteToSelf(ctx, msg); err != nil {
			c.log(LogDebug, "note to self not synced (no other devices?): "+err.Error())
		}
		return nil
	}
	if groupID, ok := sgAsGroup(bare); ok {
		res, err := client.SendGroupMessage(ctx, groupID, msg)
		if err != nil {
			return err
		}
		if res == nil {
			return fmt.Errorf("group send returned no result")
		}
		return nil
	}
	serviceID, ok := sgServiceID(bare)
	if !ok {
		return fmt.Errorf("malformed recipient id %q", bare)
	}
	res := client.SendMessage(ctx, serviceID, msg)
	if !res.WasSuccessful {
		return fmt.Errorf("%v", res.FailedSendResult)
	}
	return nil
}

const sgPNIPrefix = "PNI:"

func sgTrimPNI(bare string) (string, bool) {
	for _, prefix := range []string{sgPNIPrefix, "pni:"} {
		if strings.HasPrefix(bare, prefix) {
			return strings.TrimPrefix(bare, prefix), true
		}
	}
	return bare, false
}

func sgServiceID(bare string) (libsignalgo.ServiceID, bool) {
	trimmed, isPNI := sgTrimPNI(bare)
	parsed, err := uuid.Parse(trimmed)
	if err != nil {
		return libsignalgo.ServiceID{}, false
	}
	if isPNI {
		return libsignalgo.NewPNIServiceID(parsed), true
	}
	return libsignalgo.NewACIServiceID(parsed), true
}

func sgRecipientID(aci, pni uuid.UUID) string {
	if aci != uuid.Nil {
		return SgIDPrefix + aci.String()
	}
	return SgIDPrefix + sgPNIPrefix + pni.String()
}

func sgBareID(chatId string) string { return strings.TrimPrefix(chatId, SgIDPrefix) }

func sgAsGroup(bare string) (types.GroupIdentifier, bool) {
	trimmed, _ := sgTrimPNI(bare)
	if _, err := uuid.Parse(trimmed); err == nil {
		return "", false
	}
	return types.GroupIdentifier(bare), true
}

func sgConversationID(chatId string) (*signalpb.ConversationIdentifier, bool) {
	bare := sgBareID(chatId)
	if groupID, ok := sgAsGroup(bare); ok {
		gid, err := groupID.Bytes()
		if err != nil {
			return nil, false
		}
		return &signalpb.ConversationIdentifier{
			Identifier: &signalpb.ConversationIdentifier_ThreadGroupId{ThreadGroupId: gid[:]},
		}, true
	}
	serviceID, ok := sgServiceID(bare)
	if !ok {
		return nil, false
	}
	return &signalpb.ConversationIdentifier{
		Identifier: &signalpb.ConversationIdentifier_ThreadServiceIdBinary{
			ThreadServiceIdBinary: serviceID.Bytes(),
		},
	}, true
}

func sgChatIDFromConversation(cid *signalpb.ConversationIdentifier) (string, bool) {
	switch ident := cid.GetIdentifier().(type) {
	case *signalpb.ConversationIdentifier_ThreadServiceId:
		serviceID, err := libsignalgo.ServiceIDFromString(ident.ThreadServiceId)
		if err != nil {
			return "", false
		}
		return sgRecipientID(serviceID.ToACIAndPNI()), true
	case *signalpb.ConversationIdentifier_ThreadServiceIdBinary:
		serviceID, err := libsignalgo.ServiceIDFromBytes(ident.ThreadServiceIdBinary)
		if err != nil {
			return "", false
		}
		return sgRecipientID(serviceID.ToACIAndPNI()), true
	case *signalpb.ConversationIdentifier_ThreadGroupId:
		if len(ident.ThreadGroupId) != libsignalgo.GroupIdentifierLength {
			return "", false
		}
		raw := libsignalgo.GroupIdentifier(ident.ThreadGroupId)
		return SgIDPrefix + types.BytesToGroupIdentifier(&raw).String(), true
	}
	return "", false
}

func (c *sgConn) handleDeleteForMe(evt *events.DeleteForMe) {
	report := func(cid *signalpb.ConversationIdentifier) {
		if chatID, ok := sgChatIDFromConversation(cid); ok {
			c.listener.OnChatDeleted(chatID, true)
		}
	}
	for _, conv := range evt.GetConversationDeletes() {
		report(conv.GetConversation())
	}
	for _, conv := range evt.GetLocalOnlyConversationDeletes() {
		report(conv.GetConversation())
	}
}

func (c *sgConn) handleEvent(rawEvt events.SignalEvent) bool {
	switch evt := rawEvt.(type) {
	case *events.ChatEvent:
		c.handleChatEvent(evt)
	case *events.ContactList:
		c.handleContactList(evt)
	case *events.Receipt:
		c.handleReceipt(evt)
	case *events.ReadSelf:
		c.handleReadSelf(evt)
	case *events.DeleteForMe:
		c.handleDeleteForMe(evt)
	case *events.QueueEmpty:
		c.listener.OnContactsSynced()
	case *events.LoggedOut:
		c.log(LogWarning, "logged out by server")
		c.dropDeadDevice()
		c.setState("logged_out")
	}
	return true
}

func (c *sgConn) dropDeadDevice() {
	c.mu.Lock()
	device, container := c.device, c.container
	c.device, c.client = nil, nil
	c.mu.Unlock()
	if device != nil && container != nil {
		if err := container.DeleteDevice(context.TODO(), &device.DeviceData); err != nil {
			c.log(LogWarning, "failed to delete device row: "+err.Error())
		}
	}
}

func (c *sgConn) handleContactList(evt *events.ContactList) {
	c.mu.Lock()
	device := c.device
	c.mu.Unlock()
	for _, r := range evt.Contacts {
		if r == nil || r.ACI == uuid.Nil {
			continue
		}
		isSelf := device != nil && r.ACI == device.ACI
		name := r.ContactName
		if name == "" {
			name = r.Profile.Name
		}
		if name == "" {
			name = r.Nickname
		}
		if name == "" {
			continue
		}
		id := SgIDPrefix + r.ACI.String()
		c.mu.Lock()
		c.known[id] = true
		c.mu.Unlock()
		c.listener.OnContact(id, name, strings.TrimPrefix(r.E164, "+"), isSelf, false, true)
	}
}

func (c *sgConn) handleReceipt(evt *events.Receipt) {
	var report func(chatId string, msgId string)
	switch evt.Content.GetType() {
	case signalpb.ReceiptMessage_READ:
		report = c.listener.OnMessageRead
	case signalpb.ReceiptMessage_VIEWED:
		report = c.listener.OnMessagePlayed
	default:
		return
	}
	reader := SgIDPrefix + evt.Sender.String()
	for _, ts := range evt.Content.GetTimestamp() {
		report(reader, fmt.Sprintf("%d", ts))
	}
}

const sgReadSelfDepth = 8

func (c *sgConn) handleReadSelf(evt *events.ReadSelf) {
	byAuthor := make(map[string][]uint64, len(evt.Messages))
	order := make([]string, 0, len(evt.Messages))
	for _, r := range evt.Messages {
		aci, err := signalmeow.ParseStringOrBinaryUUID(r.GetSenderAci(), r.GetSenderAciBinary())
		if err != nil {
			continue
		}
		id := SgIDPrefix + aci.String()
		if _, seen := byAuthor[id]; !seen {
			order = append(order, id)
		}
		byAuthor[id] = append(byAuthor[id], r.GetTimestamp())
	}
	for _, id := range order {
		stamps := byAuthor[id]
		slices.Sort(stamps)
		slices.Reverse(stamps)
		stamps = slices.Compact(stamps)
		if len(stamps) > sgReadSelfDepth {
			stamps = stamps[:sgReadSelfDepth]
		}
		for _, ts := range stamps {
			c.listener.OnChatReadSelf(id, fmt.Sprintf("%d", ts))
		}
	}
}

func (c *sgConn) handleChatEvent(evt *events.ChatEvent) {
	c.mu.Lock()
	client, device := c.client, c.device
	c.mu.Unlock()
	if client == nil || device == nil {
		return
	}

	chatID := SgIDPrefix + evt.Info.ChatID
	senderID := SgIDPrefix + evt.Info.Sender.String()
	fromMe := evt.Info.Sender == device.ACI
	timeSent := int64(evt.Info.ServerTimestamp / 1000)

	c.resolveName(client, evt.Info.ChatID, chatID)

	switch content := evt.Event.(type) {
	case *signalpb.DataMessage:
		if r := content.GetReaction(); r != nil {
			emoji := r.GetEmoji()
			if r.GetRemove() {
				emoji = ""
			}
			c.listener.OnReaction(
				chatID, fmt.Sprintf("%d", r.GetTargetSentTimestamp()), senderID, emoji,
			)
			return
		}
		if d := content.GetDelete(); d != nil {
			c.listener.OnMessageDeleted(chatID, fmt.Sprintf("%d", d.GetTargetSentTimestamp()))
			return
		}
		body := sgWithMarkers(content.GetBody(), content.GetBodyRanges())
		msgID := fmt.Sprintf("%d", content.GetTimestamp())
		quotedID, quotedText := "", ""
		if q := content.GetQuote(); q != nil {
			quotedID = fmt.Sprintf("%d", q.GetId())
			quotedText = q.GetText()
		}
		if atts := content.GetAttachments(); len(atts) > 0 {
			for i, att := range atts {
				kind := sgAttachmentKind(att.GetContentType(), att.GetFlags()&uint32(signalpb.AttachmentPointer_VOICE_MESSAGE) != 0)
				caption := ""
				id := msgID
				if i == 0 {
					caption = body
				} else {
					id = fmt.Sprintf("%s-%d", msgID, i)
				}
				c.listener.OnMessage(
					chatID, id, senderID, caption,
					fromMe, timeSent, false, kind, sgFileID(att),
					0, 0, false, false, quotedID, quotedText, "", "", false,
				)
			}
			return
		}
		if text := sgContactText(content.GetContact()); text != "" {
			c.listener.OnMessage(
				chatID, msgID, senderID, text,
				fromMe, timeSent, false, "contact", "",
				0, 0, false, false, quotedID, quotedText, "", "", false,
			)
			return
		}
		if lat, lng, ok := sgParseMapLink(body); ok {
			c.listener.OnMessage(
				chatID, msgID, senderID, "",
				fromMe, timeSent, false, "location", "",
				lat, lng, false, false, quotedID, quotedText, "", "", false,
			)
			return
		}
		if body == "" {
			return
		}
		c.listener.OnMessage(
			chatID, msgID, senderID, body,
			fromMe, timeSent, false, "", "", 0, 0, false, false, quotedID, quotedText, "", "", false,
		)
	case *signalpb.EditMessage:
		edited := content.GetDataMessage()
		c.listener.OnMessage(
			chatID, fmt.Sprintf("%d", content.GetTargetSentTimestamp()), senderID,
			sgWithMarkers(edited.GetBody(), edited.GetBodyRanges()), fromMe, 0, false, "", "",
			0, 0, false, true, "", "", "", "", false,
		)
	case *signalpb.TypingMessage:
		state := "paused"
		if content.GetAction() == signalpb.TypingMessage_STARTED {
			state = "composing"
		}
		c.listener.OnChatState(chatID, senderID, state)
	}
}

func (c *sgConn) resolveName(client *signalmeow.Client, bare string, chatID string) {
	c.mu.Lock()
	seen := c.known[chatID]
	c.mu.Unlock()
	if seen {
		return
	}

	ctx := context.TODO()
	isGroup := false
	name := ""
	if groupID, ok := sgAsGroup(bare); ok {
		isGroup = true
		if group, _, err := client.RetrieveGroupByID(ctx, groupID, 0); err == nil && group != nil {
			name = group.Title
		}
	} else if aci, err := uuid.Parse(bare); err == nil {
		if profile, err := client.RetrieveProfileByID(ctx, aci, 0); err == nil && profile != nil {
			name = profile.Name
		}
	}
	if name == "" {
		return
	}
	c.mu.Lock()
	c.known[chatID] = true
	c.mu.Unlock()
	c.listener.OnContact(chatID, name, "", false, isGroup, true)
	c.listener.OnChat(chatID, name, 0, false, 0)
}

var sgRegSession string

func SignalRegisterStart(number string) string {
	c := sgGet()
	if c == nil {
		return sgErrNotInitialised
	}
	session, err := signalmeow.CreateRegistrationSession(context.TODO(), number)
	if err != nil {
		return sgUpstream(err)
	}
	sgRegSession = session.ID
	sgRegNeedsCaptcha = session.NeedsCaptcha()
	return ""
}

func SignalRegisterNeedsCaptcha() bool { return sgRegNeedsCaptcha }

var sgRegNeedsCaptcha bool

func SignalRegisterSubmitCaptcha(token string) string {
	if sgRegSession == "" {
		return sgErrNoSession
	}
	_, err := signalmeow.SubmitRegistrationCaptcha(context.TODO(), sgRegSession, token)
	if err != nil {
		return sgUpstream(err)
	}
	return ""
}

func SignalRegisterRequestCode(transport string) string {
	if sgRegSession == "" {
		return sgErrNoSession
	}
	if _, err := signalmeow.RequestRegistrationCode(context.TODO(), sgRegSession, transport); err != nil {
		return sgUpstream(err)
	}
	return ""
}

func SignalRegisterSubmitCode(number string, code string) string {
	c := sgGet()
	if c == nil {
		return sgErrNotInitialised
	}
	if sgRegSession == "" {
		return sgErrNoSession
	}
	ctx := context.TODO()
	c.mu.Lock()
	container := c.container
	c.mu.Unlock()
	if container == nil {
		return sgErrNotInitialised
	}
	session, err := signalmeow.SubmitRegistrationCode(ctx, sgRegSession, code)
	if err != nil {
		return sgUpstream(err)
	}
	if !session.Verified {
		return sgErrCodeRejected
	}
	device, err := signalmeow.RegisterPrimaryDevice(ctx, container, number, sgRegSession)
	if err != nil {
		return sgUpstream(err)
	}
	c.mu.Lock()
	c.device = device
	c.client = signalmeow.NewClient(device, c.logger(), c.handleEvent)
	c.mu.Unlock()
	sgRegSession = ""
	return ""
}

func SignalDiscoverContacts(numbers string) string {
	c, client, device := sgActive()
	if client == nil || device == nil {
		return sgErrNotRegistered
	}

	var e164s []uint64
	for _, part := range strings.Split(numbers, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		n, err := strconv.ParseUint(part, 10, 64)
		if err != nil {
			continue
		}
		e164s = append(e164s, n)
	}
	selfDigits := strings.TrimPrefix(client.Store.Number, "+")
	selfE164, selfErr := strconv.ParseUint(selfDigits, 10, 64)
	if selfErr == nil {
		e164s = append(e164s, selfE164)
	}
	if len(e164s) == 0 {
		return ""
	}

	resp, err := client.LookupPhone(context.TODO(), e164s...)
	if err != nil {
		c.log(LogError, "contact discovery failed: "+err.Error())
		return sgUpstream(err)
	}

	ctx := context.TODO()
	selfID := SignalSelfID()
	found := 0
	type sgHit struct{ id, phone string }
	hits := make([]sgHit, 0, len(resp))

	store := func(ctx context.Context) error {
		hits = hits[:0]
		found = 0
		for e164, entry := range resp {
			if entry.ACI == uuid.Nil && entry.PNI == uuid.Nil {
				continue
			}
			id := sgRecipientID(entry.ACI, entry.PNI)
			if id == selfID {
				found++
				continue
			}
			phone := fmt.Sprintf("%d", e164)
			if _, err := client.Store.RecipientStore.UpdateRecipientE164(ctx, entry.ACI, entry.PNI, "+"+phone); err != nil {
				c.log(LogWarning, "failed to store discovered recipient: "+err.Error())
			}
			hits = append(hits, sgHit{id: id, phone: phone})
			found++
		}
		return nil
	}
	if err := device.DoContactTxn(ctx, store); err != nil {
		return sgUpstream(err)
	}

	c.mu.Lock()
	for _, hit := range hits {
		c.known[hit.id] = true
	}
	c.mu.Unlock()
	for _, hit := range hits {
		c.listener.OnContact(hit.id, "", hit.phone, false, false, true)
	}
	_, selfFound := resp[selfE164]
	c.log(LogInfo, fmt.Sprintf(
		"contact discovery matched %d of %d numbers (self resolved: %t)",
		found, len(e164s), selfFound,
	))
	c.listener.OnContactsSynced()
	return ""
}

func (c *sgConn) publishSelfContact() {
	c.mu.Lock()
	client, device := c.client, c.device
	c.mu.Unlock()
	if client == nil || device == nil {
		return
	}
	name := ""
	if rec := client.Store.AccountRecord; rec != nil {
		name = strings.TrimSpace(rec.GetGivenName() + " " + rec.GetFamilyName())
		if name == "" {
			name = rec.GetUsername()
		}
	}
	if name == "" {
		if profile, err := client.RetrieveProfileByID(context.TODO(), device.ACI, time.Hour); err == nil && profile != nil {
			name = profile.Name
		}
	}
	if name == "" {
		name = device.Number
	}
	c.listener.OnContact(SignalSelfID(), name, strings.TrimPrefix(device.Number, "+"), true, false, true)
}


func sgStyleRanges(spec string) []*signalpb.BodyRange {
	if spec == "" {
		return nil
	}
	out := make([]*signalpb.BodyRange, 0, 4)
	for _, part := range strings.Split(spec, ";") {
		f := strings.Split(part, ",")
		if len(f) != 3 {
			continue
		}
		start, errS := strconv.ParseUint(f[0], 10, 32)
		length, errL := strconv.ParseUint(f[1], 10, 32)
		if errS != nil || errL != nil || length == 0 {
			continue
		}
		style := signalpb.BodyRange_ITALIC
		if f[2] == "b" {
			style = signalpb.BodyRange_BOLD
		}
		s32, l32 := uint32(start), uint32(length)
		out = append(out, &signalpb.BodyRange{
			Start:           &s32,
			Length:          &l32,
			AssociatedValue: &signalpb.BodyRange_Style_{Style: style},
		})
	}
	return out
}

func sgWithMarkers(body string, ranges []*signalpb.BodyRange) string {
	if body == "" || len(ranges) == 0 {
		return body
	}
	units := utf16.Encode([]rune(body))
	at := make([][]rune, len(units)+1)
	used := false
	for _, r := range ranges {
		style, ok := r.GetAssociatedValue().(*signalpb.BodyRange_Style_)
		if !ok {
			continue
		}
		marker := '_'
		switch style.Style {
		case signalpb.BodyRange_BOLD:
			marker = '*'
		case signalpb.BodyRange_ITALIC:
			marker = '_'
		default:
			continue
		}
		start, end := int(r.GetStart()), int(r.GetStart()+r.GetLength())
		if start < 0 || end > len(units) || end <= start {
			continue
		}
		if isLowSurrogate(units[start]) || (end < len(units) && isLowSurrogate(units[end])) {
			continue
		}
		if isSpaceUnit(units[start]) || isSpaceUnit(units[end-1]) {
			continue
		}
		if start > 0 && isWordUnit(units[start-1]) {
			continue
		}
		if end < len(units) && isWordUnit(units[end]) {
			continue
		}
		at[start] = append(at[start], marker)
		at[end] = append([]rune{marker}, at[end]...)
		used = true
	}
	if !used {
		return body
	}
	out := make([]uint16, 0, len(units)+8)
	for i := 0; i <= len(units); i++ {
		out = append(out, utf16.Encode(at[i])...)
		if i < len(units) {
			out = append(out, units[i])
		}
	}
	return string(utf16.Decode(out))
}

func isSpaceUnit(u uint16) bool { return unicode.IsSpace(rune(u)) }

func isLowSurrogate(u uint16) bool { return u >= 0xDC00 && u <= 0xDFFF }

func isWordUnit(u uint16) bool {
	r := rune(u)
	return unicode.IsLetter(r) || unicode.IsDigit(r)
}

func sgTimestamp(msgId string) uint64 {
	if ts, err := strconv.ParseUint(msgId, 10, 64); err == nil && ts > 0 {
		return ts
	}
	return uint64(time.Now().UnixMilli())
}

func sgEchoOwn(c *sgConn, chatId, msgID, text, msgType, fileID string, timeSent int64) {
	c.listener.OnMessage(
		chatId, msgID, SignalSelfID(), text, true, timeSent, false,
		msgType, fileID, 0, 0, false, false, "", "", "", "", false,
	)
	c.listener.OnChat(chatId, "", 0, false, timeSent)
}

func sgAttachmentKind(mime string, voiceNote bool) string {
	switch {
	case voiceNote, strings.HasPrefix(mime, "audio/"):
		return "audio"
	case strings.HasPrefix(mime, "image/"):
		return "image"
	case strings.HasPrefix(mime, "video/"):
		return "video"
	default:
		return "document"
	}
}

func sgFileID(ptr *signalpb.AttachmentPointer) string {
	raw, err := proto.Marshal(ptr)
	if err != nil {
		return ""
	}
	return "sg:" + base64.StdEncoding.EncodeToString(raw)
}

func sgParseFileID(fileID string) (*signalpb.AttachmentPointer, error) {
	encoded := strings.TrimPrefix(fileID, "sg:")
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return nil, err
	}
	ptr := &signalpb.AttachmentPointer{}
	if err := proto.Unmarshal(raw, ptr); err != nil {
		return nil, err
	}
	return ptr, nil
}

const sgMaxAttachmentBytes = 100 << 20

func SignalSendAttachment(
	chatId string, msgId string, path string, caption string, mime string, voiceNote bool,
) string {
	c, client, _ := sgActive()
	if client == nil {
		return ""
	}

	timestamp := sgTimestamp(msgId)
	msgID := fmt.Sprintf("%d", timestamp)

	info, serr := os.Stat(path)
	if serr != nil {
		return sgFailSend(c, "attachment stat", chatId, msgID, serr)
	}
	if info.Size() > sgMaxAttachmentBytes {
		c.log(LogError, fmt.Sprintf("attachment too large: %d bytes (cap %d)", info.Size(), sgMaxAttachmentBytes))
		c.listener.OnMessageSendFailed(chatId, msgID)
		return ""
	}
	body, err := os.ReadFile(path)
	if err != nil {
		return sgFailSend(c, "attachment read", chatId, msgID, err)
	}

	ctx := context.TODO()
	ptr, err := client.UploadAttachment(ctx, body)
	if err != nil {
		return sgFailSend(c, "attachment upload", chatId, msgID, err)
	}
	ptr.ContentType = proto.String(mime)
	ptr.FileName = proto.String(filepath.Base(path))
	if voiceNote {
		flags := uint32(signalpb.AttachmentPointer_VOICE_MESSAGE)
		ptr.Flags = &flags
	}

	dm := &signalpb.DataMessage{
		Timestamp:   &timestamp,
		Attachments: []*signalpb.AttachmentPointer{ptr},
	}
	if caption != "" {
		dm.Body = proto.String(caption)
	}
	sgEchoOwn(c, chatId, msgID, caption, sgAttachmentKind(mime, voiceNote), sgFileID(ptr), int64(timestamp/1000))
	if err := sgSend(c, client, chatId, &signalpb.Content{
		Content: &signalpb.Content_DataMessage{DataMessage: dm},
	}); err != nil {
		return sgFailSend(c, "attachment send", chatId, msgID, err)
	}
	return msgID
}

func SignalDownloadAttachment(chatId string, msgId string, fileId string) {
	c := sgGet()
	if c == nil {
		return
	}
	fail := func(why string, err error) {
		c.log(LogWarning, fmt.Sprintf("signal download %s: %v", why, err))
		c.listener.OnFileDownloaded(chatId, msgId, "", 3)
	}

	ptr, err := sgParseFileID(fileId)
	if err != nil {
		fail("bad file id", err)
		return
	}

	dir := c.path + "/media"
	if err := os.MkdirAll(dir, os.ModePerm); err != nil {
		fail("mkdir", err)
		return
	}
	out := dir + "/" + safeName(msgId, false) + sgExtFor(ptr.GetContentType())
	f, err := os.Create(out)
	if err != nil {
		fail("create", err)
		return
	}
	_, err = signalmeow.DownloadAttachmentWithPointer(context.TODO(), ptr, nil, f)
	closeErr := f.Close()
	if err != nil {
		os.Remove(out)
		fail("fetch", err)
		return
	}
	if closeErr != nil {
		fail("close", closeErr)
		return
	}
	c.listener.OnFileDownloaded(chatId, msgId, out, 2)
}

func sgExtFor(mime string) string {
	switch mime {
	case "audio/aac", "audio/mp4", "audio/m4a":
		return ".m4a"
	case "audio/ogg", "audio/ogg; codecs=opus":
		return ".ogg"
	}
	return extFromMime(mime, "")
}

func sgTargetAuthor(chatId, senderId string) string {
	bare, _ := sgTrimPNI(sgAuthorID(chatId, senderId))
	return bare
}

func sgAuthorID(chatId, senderId string) string {
	id := senderId
	if id == "" {
		id = chatId
	}
	return strings.TrimPrefix(id, SgIDPrefix)
}

func SignalReact(chatId string, msgId string, senderId string, emoji string) {
	c, client, _ := sgActive()
	if client == nil {
		return
	}
	target, err := strconv.ParseUint(msgId, 10, 64)
	if err != nil {
		c.log(LogError, "react: bad target id "+msgId)
		return
	}
	author := sgTargetAuthor(chatId, senderId)
	remove := emoji == ""
	now := uint64(time.Now().UnixMilli())
	dm := &signalpb.DataMessage{
		Timestamp: &now,
		Reaction: &signalpb.DataMessage_Reaction{
			Emoji:               proto.String(emoji),
			Remove:              &remove,
			TargetAuthorAci:     proto.String(author),
			TargetSentTimestamp: &target,
		},
	}
	if err := sgSend(c, client, chatId, signalmeow.WrapDataMessage(dm)); err != nil {
		c.log(LogError, "react failed: "+err.Error())
		return
	}
	c.listener.OnReaction(chatId, msgId, SignalSelfID(), emoji)
}

func SignalDelete(chatId string, msgId string) {
	c, client, _ := sgActive()
	if client == nil {
		return
	}
	target, err := strconv.ParseUint(msgId, 10, 64)
	if err != nil {
		return
	}
	now := uint64(time.Now().UnixMilli())
	dm := &signalpb.DataMessage{
		Timestamp: &now,
		Delete:    &signalpb.DataMessage_Delete{TargetSentTimestamp: &target},
	}
	if err := sgSend(c, client, chatId, signalmeow.WrapDataMessage(dm)); err != nil {
		c.log(LogError, "delete failed: "+err.Error())
		return
	}
	c.listener.OnMessageDeleted(chatId, msgId)
}

func SignalDeleteChat(chatId string, recent string) bool {
	c, client, device := sgActive()
	if client == nil || device == nil {
		return false
	}
	conversation, ok := sgConversationID(chatId)
	if !ok {
		return false
	}
	var mostRecent []*signalpb.AddressableMessage
	for _, line := range strings.Split(recent, "\n") {
		senderId, stamp, found := strings.Cut(line, "|")
		if !found {
			continue
		}
		ts, err := strconv.ParseUint(stamp, 10, 64)
		if err != nil {
			continue
		}
		author, err := libsignalgo.ServiceIDFromString(sgAuthorID(chatId, senderId))
		if err != nil {
			continue
		}
		mostRecent = append(mostRecent, &signalpb.AddressableMessage{
			Author: &signalpb.AddressableMessage_AuthorServiceIdBinary{
				AuthorServiceIdBinary: author.Bytes(),
			},
			SentTimestamp: proto.Uint64(ts),
		})
	}
	sync := &signalpb.SyncMessage{
		Content: &signalpb.SyncMessage_DeleteForMe_{
			DeleteForMe: &signalpb.SyncMessage_DeleteForMe{
				ConversationDeletes: []*signalpb.SyncMessage_DeleteForMe_ConversationDelete{{
					Conversation:       conversation,
					MostRecentMessages: mostRecent,
					IsFullDelete:       proto.Bool(true),
				}},
			},
		},
	}
	res := client.SendMessage(
		context.TODO(), device.ACIServiceID(), signalmeow.WrapSyncMessage(sync),
	)
	if !res.WasSuccessful {
		c.log(LogWarning, fmt.Sprintf("delete chat error %v", res.Error))
		return false
	}
	return true
}

func SignalEdit(chatId string, msgId string, newText string, styles string, fileIds string, preview *Preview) bool {
	c, client, _ := sgActive()
	if client == nil {
		return false
	}
	target, err := strconv.ParseUint(msgId, 10, 64)
	if err != nil {
		return false
	}
	now := uint64(time.Now().UnixMilli())
	ranges := sgStyleRanges(styles)
	dm := &signalpb.DataMessage{
		Timestamp:  &now,
		Body:       proto.String(newText),
		BodyRanges: ranges,
	}
	if fileIds != "" {
		for _, fileId := range strings.Split(fileIds, "\n") {
			ptr, err := sgParseFileID(fileId)
			if err != nil {
				c.log(LogError, "edit attachment decode failed: "+err.Error())
				return false
			}
			dm.Attachments = append(dm.Attachments, ptr)
		}
	}
	sgApplyPreview(c, client, dm, preview)
	edit := &signalpb.EditMessage{
		TargetSentTimestamp: &target,
		DataMessage:         dm,
	}
	if err := sgSend(c, client, chatId, signalmeow.WrapEditMessage(edit)); err != nil {
		c.log(LogError, "edit failed: "+err.Error())
		return false
	}
	c.listener.OnMessage(
		chatId, msgId, SignalSelfID(), sgWithMarkers(newText, ranges), true, 0, false,
		"", "", 0, 0, false, true, "", "", "", "", false,
	)
	return true
}

func SignalMarkRead(chatId string, msgId string) {
	c, client, _ := sgActive()
	if client == nil {
		return
	}
	ts, err := strconv.ParseUint(msgId, 10, 64)
	if err != nil {
		return
	}
	if err := sgSend(c, client, chatId, signalmeow.ReadReceptMessageForTimestamps([]uint64{ts})); err != nil {
		c.log(LogDebug, "read receipt failed: "+err.Error())
	}
}

func SignalSetTyping(chatId string, typing bool) {
	c, client, _ := sgActive()
	if client == nil {
		return
	}
	if err := sgSend(c, client, chatId, signalmeow.TypingMessage(typing)); err != nil {
		c.log(LogDebug, "typing failed: "+err.Error())
	}
}

type sgKeyReading struct {
	name string
	key  []byte
}

func sgKeyReadings(candidates [][]byte) []sgKeyReading {
	const svrMasterKeyInfo = "20240801_SIGNAL_SVR_MASTER_KEY"
	out := make([]sgKeyReading, 0, len(candidates)*3)
	for _, raw := range candidates {
		out = append(out, sgKeyReading{name: "svr key", key: raw})
		if derived, err := sgHKDF(raw, svrMasterKeyInfo); err == nil {
			out = append(out, sgKeyReading{name: "entropy pool", key: derived})
		}
		if hexed, err := sgHKDF([]byte(hex.EncodeToString(raw)), svrMasterKeyInfo); err == nil {
			out = append(out, sgKeyReading{name: "entropy pool as hex", key: hexed})
		}
	}
	return out
}

func sgHKDF(ikm []byte, info string) ([]byte, error) {
	out := make([]byte, 32)
	r := hkdf.New(sha256.New, ikm, nil, []byte(info))
	if _, err := io.ReadFull(r, out); err != nil {
		return nil, err
	}
	return out, nil
}

func SignalRestoreFromPIN(pin string) string {
	c, client, device := sgActive()
	if client == nil || device == nil {
		return sgErrNotRegistered
	}

	ctx := context.TODO()
	candidates, err := client.RestoreMasterKeyFromSVR2(ctx, pin)
	if err != nil {
		return sgRestoreError(err)
	}

	var masterKey []byte
	var lastErr error
	for _, candidate := range sgKeyReadings(candidates) {
		if update, err := client.FetchStorage(ctx, candidate.key, 0, nil); err == nil && update != nil {
			masterKey = candidate.key
			c.log(LogInfo, "storage manifest opened with the "+candidate.name+" reading")
			break
		} else {
			lastErr = err
			c.log(LogWarning, fmt.Sprintf(
				"key reading %q (%d bytes) rejected: %v", candidate.name, len(candidate.key), err))
		}
	}
	if masterKey == nil {
		c.log(LogError, fmt.Sprintf("key recovered but manifest not readable: %v", lastErr))
		return sgErrManifestLocked
	}

	device.MasterKey = masterKey
	c.mu.Lock()
	container := c.container
	c.mu.Unlock()
	if container == nil {
		return sgErrNotInitialised
	}
	if err := container.PutDevice(ctx, &device.DeviceData); err != nil {
		c.log(LogError, "failed to store recovered key: "+err.Error())
		return sgErrStoreFailed
	}
	client.SyncStorage(ctx)
	c.log(LogInfo, "master key recovered from SVR2, storage sync started")
	return ""
}

func sgMyProfile() *types.Profile {
	_, client, device := sgActive()
	if client == nil || device == nil {
		return nil
	}
	profile, err := client.RetrieveProfileByID(context.TODO(), device.ACI, time.Hour)
	if err != nil {
		return nil
	}
	return profile
}

func SignalMyName() string {
	if p := sgMyProfile(); p != nil {
		return p.Name
	}
	return ""
}

func SignalMyAbout() string {
	if p := sgMyProfile(); p != nil {
		return p.About
	}
	return ""
}

func SignalSetProfile(name string, about string, discoverable bool) bool {
	c, client, _ := sgActive()
	if client == nil {
		return false
	}
	if err := client.UpdateProfile(context.TODO(), name, about, discoverable); err != nil {
		c.log(LogError, "profile update failed: "+err.Error())
		return false
	}
	return true
}

func SignalSetDiscoverable(discoverable bool) bool {
	c, client, _ := sgActive()
	if client == nil {
		return false
	}
	if err := client.SetDiscoverableByPhoneNumber(context.TODO(), discoverable); err != nil {
		c.log(LogError, "discoverability update failed: "+err.Error())
		return false
	}
	return true
}

func SignalMyPhone() string {
	c := sgGet()
	if c == nil {
		return ""
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.device == nil {
		return ""
	}
	return c.device.Number
}

func sgParseMapLink(body string) (float64, float64, bool) {
	coords, hasPrefix := strings.CutPrefix(strings.TrimSpace(body), sgMapLinkPrefix)
	if !hasPrefix {
		return 0, 0, false
	}
	latText, lngText, found := strings.Cut(coords, ",")
	if !found {
		return 0, 0, false
	}
	lat, err := strconv.ParseFloat(latText, 64)
	if err != nil {
		return 0, 0, false
	}
	lng, err := strconv.ParseFloat(lngText, 64)
	if err != nil {
		return 0, 0, false
	}
	if lat < -90 || lat > 90 || lng < -180 || lng > 180 {
		return 0, 0, false
	}
	return lat, lng, true
}

const sgMapLinkPrefix = "https://maps.google.com/?q="

func sgContactText(cards []*signalpb.DataMessage_Contact) string {
	var people []string
	for _, card := range cards {
		var lines []string
		if name := strings.TrimSpace(sgContactName(card)); name != "" {
			lines = append(lines, name)
		}
		for _, phone := range card.GetNumber() {
			if value := strings.TrimSpace(phone.GetValue()); value != "" {
				lines = append(lines, value)
			}
		}
		if len(lines) > 0 {
			people = append(people, strings.Join(lines, "\n"))
		}
	}
	return strings.Join(people, "\n\n")
}

func sgContactName(card *signalpb.DataMessage_Contact) string {
	name := card.GetName()
	if nick := name.GetNickname(); nick != "" {
		return nick
	}
	parts := []string{name.GetGivenName(), name.GetMiddleName(), name.GetFamilyName()}
	var named []string
	for _, part := range parts {
		if part != "" {
			named = append(named, part)
		}
	}
	if len(named) > 0 {
		return strings.Join(named, " ")
	}
	if org := card.GetOrganization(); org != "" {
		return org
	}
	if len(card.GetNumber()) > 0 {
		return card.GetNumber()[0].GetValue()
	}
	return ""
}

func SignalSendLocation(chatId string, msgId string, latitude float64, longitude float64) string {
	c, client, _ := sgActive()
	if client == nil {
		return ""
	}
	text := fmt.Sprintf("%s%.6f,%.6f", sgMapLinkPrefix, latitude, longitude)
	timestamp := sgTimestamp(msgId)
	dm := &signalpb.DataMessage{Body: proto.String(text), Timestamp: &timestamp}
	msgID := fmt.Sprintf("%d", timestamp)
	if err := sgSend(c, client, chatId, signalmeow.WrapDataMessage(dm)); err != nil {
		return sgFailSend(c, "location send", chatId, msgID, err)
	}
	return msgID
}

func SignalSendContact(chatId string, msgId string, name string, numbers string) string {
	c, client, _ := sgActive()
	if client == nil {
		return ""
	}

	phones := make([]*signalpb.DataMessage_Contact_Phone, 0, 2)
	for _, number := range strings.Split(numbers, ",") {
		number = strings.TrimSpace(number)
		if number == "" {
			continue
		}
		phoneType := signalpb.DataMessage_Contact_Phone_MOBILE
		phones = append(phones, &signalpb.DataMessage_Contact_Phone{
			Value: proto.String(number),
			Type:  &phoneType,
		})
	}
	if len(phones) == 0 {
		return ""
	}

	timestamp := sgTimestamp(msgId)
	dm := &signalpb.DataMessage{
		Timestamp: &timestamp,
		Contact: []*signalpb.DataMessage_Contact{{
			Name:   &signalpb.DataMessage_Contact_Name{GivenName: proto.String(name)},
			Number: phones,
		}},
	}
	msgID := fmt.Sprintf("%d", timestamp)
	if err := sgSend(c, client, chatId, signalmeow.WrapDataMessage(dm)); err != nil {
		return sgFailSend(c, "contact send", chatId, msgID, err)
	}
	return msgID
}

func SignalLookupNumber(number string) string {
	c, client, _ := sgActive()
	if client == nil {
		return sgLookupFailed
	}
	e164, err := strconv.ParseUint(strings.TrimPrefix(number, "+"), 10, 64)
	if err != nil {
		return ""
	}
	resp, err := client.LookupPhone(context.TODO(), e164)
	if err != nil {
		c.log(LogWarning, "number lookup failed: "+err.Error())
		return sgLookupFailed
	}
	entry, ok := resp[e164]
	if !ok || (entry.ACI == uuid.Nil && entry.PNI == uuid.Nil) {
		return ""
	}
	aci, pni := entry.ACI, entry.PNI
	if merged, err := client.Store.RecipientStore.UpdateRecipientE164(
		context.TODO(), aci, pni, "+"+number,
	); err != nil {
		c.log(LogWarning, "failed to store looked-up recipient: "+err.Error())
	} else if merged != nil {
		if merged.ACI != uuid.Nil {
			aci = merged.ACI
		}
		if merged.PNI != uuid.Nil {
			pni = merged.PNI
		}
	}
	id := sgRecipientID(aci, pni)
	c.mu.Lock()
	c.known[id] = true
	c.mu.Unlock()
	return id
}
