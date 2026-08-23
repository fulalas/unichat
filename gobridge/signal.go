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

// Signal rides alongside WhatsApp in this package because gomobile binds one
// aar per Go package: a second package would mean a second .so, and both would
// then link their own copy of the Go runtime into the same process.

type sgConn struct {
	mu        sync.Mutex
	client    *signalmeow.Client
	device    *sgstore.Device
	container *sgstore.Container
	// Held so logout can close it: Container exposes no Close, and the caller
	// deletes the whole directory straight afterwards.
	sql      *sql.DB
	listener EventListener
	path     string
	state    string
	cancel   context.CancelFunc
	// Cancels a link in progress. Held so leaving the screen closes the
	// provisioning websocket instead of leaving it waiting for a scan.
	linkCancel context.CancelFunc
	// Names already pushed to the listener. The storage-service sync covers
	// saved contacts, but anyone else — and every group — is resolved lazily
	// from the first message they send, which must not refetch on every later one.
	known map[string]bool
}

var (
	sgMu   sync.Mutex
	sgSelf *sgConn
)

// SgIDPrefix namespaces Signal rows in the shared Db, the same way Telegram
// uses "tg:". Kotlin builds the same ids, so both sides must agree.
const SgIDPrefix = "sg:"

// Errors that reach the UI travel as codes, never as sentences: Kotlin maps them
// through strings.xml, which is what makes them translatable. A code may carry
// one argument after a ':'. Anything raised by signalmeow or the server has no
// code of its own, so it rides behind "upstream:" and is shown verbatim.
const (
	sgErrNotInitialised = "not_initialised"
	sgErrNoSession      = "no_session"
	sgErrCodeRejected   = "code_rejected"
	sgErrNotRegistered  = "not_registered"
	sgErrNoMasterKey    = "no_master_key"
	sgErrNoManifest     = "no_manifest"
	sgErrManifestLocked = "manifest_locked"
	sgErrStoreFailed    = "store_failed"
	sgErrNoBackup       = "no_backup"
	sgErrWrongPIN       = "wrong_pin"
)

func sgUpstream(err error) string { return "upstream:" + err.Error() }

// sgLookupFailed is a number lookup that could not be made, as opposed to "" —
// "this number has no Signal account". Reported as "" it reached the user as
// "Not on Signal", which is the one thing it must never say for a question
// nobody asked. Kotlin tests for the same word (Bridge.NUMBER_LOOKUP_FAILED),
// so both sides must agree.
const sgLookupFailed = "failed"

// sgRestoreError keeps the two SVR2 outcomes the user can act on — a mistyped
// PIN and an account with no backup at all — apart from everything else, which
// is only worth showing raw.
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

// sgActive is the guard every exported Signal call starts with: the connection,
// its client and its device, or nils when the account is not ready. Written out
// in full it was five lines repeated at fourteen call sites.
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

// sgLogWriter turns zerolog's output into OnLog lines so signalmeow's
// diagnostics land in logcat with everything else instead of stderr, which is
// not collected on Android.
type sgLogWriter struct{ c *sgConn }

func (w *sgLogWriter) Write(p []byte) (int, error) {
	w.c.log(LogDebug, string(p))
	return len(p), nil
}

func (c *sgConn) logger() zerolog.Logger {
	// Info, not the zerolog default: signalmeow traces every storage-service
	// record it handles, which on a real account means dumping the whole
	// address book — names and phone numbers — into logcat, and drowning every
	// other line in the process.
	return zerolog.New(&sgLogWriter{c: c}).Level(zerolog.InfoLevel).With().Timestamp().Logger()
}

// SignalInit opens the Signal store and, if this account was already
// registered, restores the client. It does not connect; call SignalConnect for that.
// Returns false when the store cannot be opened.
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
	if len(devices) > 0 {
		c.device = devices[0]
		c.client = signalmeow.NewClient(c.device, c.logger(), c.handleEvent)
	}

	sgMu.Lock()
	sgSelf = c
	sgMu.Unlock()
	return true
}

// SignalHasSession reports whether this account is already registered, so the
// UI can skip the setup screen.
func SignalHasSession() bool {
	c := sgGet()
	if c == nil {
		return false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.device != nil
}

// SignalSelfID is the linked account's ACI, namespaced like every other id the
// bridge hands to Kotlin.
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

// SignalConnect starts the receive loops. Blocking until the socket is up or
// the attempt fails.
func SignalConnect() bool {
	c, client, _ := sgActive()
	if client == nil {
		return false
	}

	c.setState("connecting")
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

	// StartReceiveLoops returns before the socket is up, so the first status it
	// reports is what settles this call. Buffered and written at most once, so
	// the loop below never blocks on a caller that has already given up.
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
				// Deliberately not settling: signalmeow reports a drop while it
				// is still dialling, and reporting failure on the first one gave
				// up on a connection that went on to come up.
			case signalmeow.SignalConnectionEventLoggedOut:
				// The primary device unlinked us. The stored keys are dead;
				// keeping them would make every later connect fail the same way.
				c.log(LogWarning, "unlinked by the primary device")
				client.ClearKeysAndDisconnect(context.TODO())
				c.mu.Lock()
				c.device, c.client = nil, nil
				c.mu.Unlock()
				c.setState("logged_out")
				settle(false)
			}
		}
		// The loop only closes the channel when it has stopped for good.
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

// SignalLinkStart links this app to an existing Signal account as a companion
// device — what Signal Desktop does — instead of registering it as the
// account's primary.
//
// This is the only way to see the account's own contact list. The primary hands
// over the account entropy pool in the provisioning message, and that is what
// the storage service (contacts, groups, settings) is encrypted with.
// Registering mints a fresh pool instead, which leaves the account's existing
// contact list on the server unreadable — so a registered account knows only
// the people number lookup can find, and never anyone who hides their number.
//
// The QR payload arrives through OnQrCode, for the official app to scan. The
// linked device's own state arrives through OnStateChanged. Non-blocking.
func SignalLinkStart(deviceName string) {
	c := sgGet()
	if c == nil {
		return
	}
	c.mu.Lock()
	container := c.container
	// One at a time: leaving the previous websocket open meant two QR codes
	// racing for the same screen, and a scan landing on the abandoned one
	// linked the account behind the user's back.
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
		// allowBackup false: this client does not implement the backup
		// transfer, and asking for one makes the primary wait on it.
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

// SignalLinkStop abandons a link in progress, for a screen the user left.
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

// adoptLinkedDevice picks up the device provisioning has just written and makes
// it this connection's own, then reports "linked" so the app can connect.
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

// SignalSyncContacts reads the account's contact list off the storage service.
//
// Only a linked device can: it is encrypted with the account entropy pool the
// primary handed over. This is where everyone who does not publish their phone
// number comes from — number lookup alone can never see them. Blocking.
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
	// Read the manifest before syncing, because SyncStorage swallows its own
	// errors: whether it decrypts is the only proof the stored key is the
	// account's, and that is what tells the UI the list is available at all.
	update, err := client.FetchStorage(ctx, device.MasterKey, 0, nil)
	if err != nil {
		c.log(LogWarning, "stored contact list not readable: "+err.Error())
		return false
	}
	if update == nil {
		c.log(LogInfo, "no stored contact list for this account")
		return false
	}
	// ProcessStorage, not SyncStorage: that would fetch the whole manifest and
	// every record again, on every connect, for what we already have here.
	if err := client.ProcessStorage(ctx, update); err != nil {
		c.log(LogWarning, "storage sync failed: "+err.Error())
		return false
	}
	// Now that the account record is in, the account's own row can carry the
	// name the user actually set rather than their number.
	c.publishSelfContact()
	c.listener.OnContactsSynced()
	return true
}

// SignalDisconnect drops the socket but keeps the linked device.
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

// SignalLogout unlinks this device and clears its keys.
func SignalLogout() {
	c := sgGet()
	if c == nil {
		return
	}
	ctx := context.TODO()
	c.mu.Lock()
	client, device := c.client, c.device
	c.mu.Unlock()
	if client != nil {
		client.ClearKeysAndDisconnect(ctx)
	}
	// Clearing the keys is not a logout on its own: the device row survives, so
	// the next start finds it, reports a live session and offers no way to link
	// again — an account permanently unable to connect.
	if device != nil {
		if err := c.container.DeleteDevice(ctx, &device.DeviceData); err != nil {
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

	// Close the store and forget the connection. Kotlin deletes the whole
	// signal directory straight after this; leaving the sqlite handle open on
	// the unlinked file meant a re-registration in the same session wrote into
	// a database that no longer existed, and vanished at the next launch.
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

// SignalSendText sends a plain text message and returns the sent message id, or
// "" on failure.
// SignalSendTextQuoted sends text, optionally as a reply. quotedId is the
// original's timestamp and quotedSender its author, both of which Signal needs
// to draw the quote on the other end.
func SignalSendTextQuoted(chatId, text, styles, quotedId, quotedText, quotedSender string) string {
	c, client, device := sgActive()
	if client == nil || device == nil {
		return ""
	}
	_ = c

	// Signal identifies a message by the timestamp the sender stamped on it;
	// there is no separate server-assigned id to report back.
	timestamp := uint64(time.Now().UnixMilli())
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
	msg := signalmeow.WrapDataMessage(dm)

	if err := sgSend(c, client, chatId, msg); err != nil {
		c.log(LogError, "send failed: "+err.Error())
		return ""
	}
	msgID := fmt.Sprintf("%d", timestamp)
	// Stored with its markers, or the sender's own bubble would be the only
	// place the formatting is missing.
	sgEchoOwn(c, chatId, msgID, sgWithMarkers(text, ranges), "", "", int64(timestamp/1000), quotedId, quotedText)
	return msgID
}

// sgSend routes a Content to a group or a single recipient. Shared so text and
// attachments cannot drift apart on addressing.
func sgSend(c *sgConn, client *signalmeow.Client, chatId string, msg *signalpb.Content) error {
	ctx := context.TODO()
	bare := sgBareID(chatId)
	// Note to self goes out as a sync message rather than a normal send: there
	// is no other party to encrypt for, so SendMessage fails with an empty
	// recipient.
	//
	// Best-effort: with no other device on the account there is nothing to
	// deliver to and the server answers 400, which is not a failure of the
	// message — the local copy the caller stores is the whole of a note to self
	// on a single-device account. Reporting the error instead made every send
	// to yourself fail.
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
	trimmed, isPNI := sgTrimPNI(bare)
	parsed, err := uuid.Parse(trimmed)
	if err != nil {
		return fmt.Errorf("malformed recipient id %q", bare)
	}
	serviceID := libsignalgo.NewACIServiceID(parsed)
	if isPNI {
		serviceID = libsignalgo.NewPNIServiceID(parsed)
	}
	res := client.SendMessage(ctx, serviceID, msg)
	if !res.WasSuccessful {
		return fmt.Errorf("%v", res.FailedSendResult)
	}
	return nil
}

// sgPNIPrefix marks a chat known only by PNI. Contact discovery returns an ACI
// only for people who have shared it; everyone else comes back PNI-only, and a
// PNI is a perfectly good address to message — it just is not interchangeable
// with an ACI, so the id has to say which one it holds.
//
// Spelled exactly as libsignalgo.ServiceID.String() spells it, uppercase,
// because incoming 1:1 chat ids come straight from that. A lowercase constant
// here silently forked every PNI contact in two: the discovered row under one
// spelling and their messages under the other, with replies routed at a
// nonexistent group because the id no longer parsed as a UUID.
const sgPNIPrefix = "PNI:"

// sgTrimPNI strips the PNI tag in either case, so ids written by an older build
// still resolve.
func sgTrimPNI(bare string) (string, bool) {
	for _, prefix := range []string{sgPNIPrefix, "pni:"} {
		if strings.HasPrefix(bare, prefix) {
			return strings.TrimPrefix(bare, prefix), true
		}
	}
	return bare, false
}

func sgRecipientID(aci, pni uuid.UUID) string {
	if aci != uuid.Nil {
		return SgIDPrefix + aci.String()
	}
	return SgIDPrefix + sgPNIPrefix + pni.String()
}

// sgBareID strips the "sg:" the Kotlin side puts on every Signal id.
func sgBareID(chatId string) string { return strings.TrimPrefix(chatId, SgIDPrefix) }

// sgAsGroup decides whether an id names a group. A 1:1 chat is keyed by the
// other party's ACI, which is a UUID; a group is keyed by its base64 group
// identifier, which is not.
func sgAsGroup(bare string) (types.GroupIdentifier, bool) {
	trimmed, _ := sgTrimPNI(bare)
	if _, err := uuid.Parse(trimmed); err == nil {
		return "", false
	}
	return types.GroupIdentifier(bare), true
}

func (c *sgConn) handleEvent(rawEvt events.SignalEvent) bool {
	switch evt := rawEvt.(type) {
	case *events.ChatEvent:
		c.handleChatEvent(evt)
	case *events.ContactList:
		c.handleContactList(evt)
	case *events.QueueEmpty:
		c.listener.OnContactsSynced()
	case *events.LoggedOut:
		c.log(LogWarning, "logged out by server")
		c.setState("logged_out")
	}
	return true
}

// handleContactList turns the storage-service sync into contact rows. Signal
// sends no chat list on registration, so without this the app knows a name only
// for people who have already messaged it.
func (c *sgConn) handleContactList(evt *events.ContactList) {
	c.mu.Lock()
	device := c.device
	c.mu.Unlock()
	for _, r := range evt.Contacts {
		if r == nil || r.ACI == uuid.Nil {
			continue
		}
		// The account's own record is in the list too. Reported as an ordinary
		// contact it cleared the is_self flag, which is what keeps the user out
		// of their own contact search.
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
		// Bare digits: the contacts table stores the number without '+', and the
		// query that reads it adds one back.
		c.listener.OnContact(id, name, strings.TrimPrefix(r.E164, "+"), isSelf, false, true)
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
			// One row per attachment, because the app's message model carries a
			// single file. The caption rides on the first, matching how the
			// WhatsApp side splits an album.
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
		if body == "" {
			// Reactions, receipts and timer changes all arrive as DataMessages
			// with no body; those are handled elsewhere or not at all yet.
			return
		}
		c.listener.OnMessage(
			chatID, msgID, senderID, body,
			fromMe, timeSent, false, "", "", 0, 0, false, false, quotedID, quotedText, "", "", false,
		)
	case *signalpb.EditMessage:
		edited := content.GetDataMessage()
		// timeSent 0, not the edit's own timestamp: upsertMessage overwrites
		// time_sent with anything above zero, which moved the edited message to
		// the bottom of the chat. The WhatsApp path does the same for the same
		// reason.
		c.listener.OnMessage(
			chatID, fmt.Sprintf("%d", content.GetTargetSentTimestamp()), senderID,
			edited.GetBody(), fromMe, 0, false, "", "",
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

// resolveName fetches a chat's display name the first time it is seen. Signal
// sends no contact list to a linked device, so without this every chat would
// show as a bare UUID.
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
		// Marked known only on success: a transient fetch failure used to leave
		// the chat titled with a raw UUID for the rest of the process.
		return
	}
	c.mu.Lock()
	c.known[chatID] = true
	c.mu.Unlock()
	c.listener.OnContact(chatID, name, "", false, isGroup, true)
	c.listener.OnChat(chatID, name, 0, false, 0)
}

// --- Registration (primary device) -----------------------------------------
//
// Registering claims the phone number for this client. Signal permits exactly
// one primary per number, so this unregisters the official app on it. Kept
// separate from the linking calls above; the UI offers one or the other.

var sgRegSession string

// SignalRegisterStart opens a verification session. Returns "" on success, or
// an error code for the UI. Blocking.
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

// SignalRegisterNeedsCaptcha reports whether the server is withholding the code
// until a captcha token is submitted.
func SignalRegisterNeedsCaptcha() bool { return sgRegNeedsCaptcha }

var sgRegNeedsCaptcha bool

// SignalRegisterSubmitCaptcha hands over the token from Signal's captcha page.
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

// SignalRegisterRequestCode asks for the code by "sms" or "voice".
func SignalRegisterRequestCode(transport string) string {
	if sgRegSession == "" {
		return sgErrNoSession
	}
	if _, err := signalmeow.RequestRegistrationCode(context.TODO(), sgRegSession, transport); err != nil {
		return sgUpstream(err)
	}
	return ""
}

// SignalRegisterSubmitCode submits the received code and, once the session is
// verified, registers the account and stores it. Returns "" on success.
func SignalRegisterSubmitCode(number string, code string) string {
	c := sgGet()
	if c == nil {
		return sgErrNotInitialised
	}
	if sgRegSession == "" {
		return sgErrNoSession
	}
	ctx := context.TODO()
	session, err := signalmeow.SubmitRegistrationCode(ctx, sgRegSession, code)
	if err != nil {
		return sgUpstream(err)
	}
	if !session.Verified {
		return sgErrCodeRejected
	}
	device, err := signalmeow.RegisterPrimaryDevice(ctx, c.container, number, sgRegSession)
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

// SignalDiscoverContacts asks Signal which of the given phone numbers have
// accounts, and reports each hit through OnContact so it becomes searchable.
//
// A freshly registered primary has nothing to sync: storage-service data is
// encrypted with a master key derived from the account entropy pool, and
// registering mints a new one, so the previous manifest is unreadable by
// design. Discovery against the device's own address book is the only way this
// account learns who is on Signal.
//
// numbers is comma-separated E.164 without the leading '+'. Blocking.
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
	// Always include our own number. It is guaranteed to be registered, so if
	// the lookup comes back without it the request itself is wrong rather than
	// the address book simply having no Signal users in it.
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

	// One transaction for the whole address book. A phone with a thousand
	// contacts meant a thousand separate sqlite commits, each one an fsync.
	// Only the store writes are in here: the OnContact callbacks below cross
	// into Kotlin, which must not happen with the transaction open.
	store := func(ctx context.Context) error {
		// Reset first: DoTxn does not retry today, but if it ever did, appending
		// again would report every discovered contact twice.
		hits = hits[:0]
		found = 0
		for e164, entry := range resp {
			if entry.ACI == uuid.Nil && entry.PNI == uuid.Nil {
				continue
			}
			// Our own number is only in the lookup as a control that the request
			// worked. Reporting it as a contact would overwrite the self row
			// publishSelfContact wrote — with a blank name, since the address
			// book rarely holds your own number — and the note-to-self chat
			// would fall back to showing a bare ACI.
			id := sgRecipientID(entry.ACI, entry.PNI)
			if id == selfID {
				found++
				continue
			}
			// Digits only: the contacts table stores a bare number and the query
			// that reads it prepends the '+', so storing one here showed "++34…".
			phone := fmt.Sprintf("%d", e164)
			// Teach signalmeow the number/ACI/PNI mapping, so a later message
			// from this person is recognised as the same recipient instead of
			// opening a second chat.
			if _, err := client.Store.RecipientStore.UpdateRecipientE164(ctx, entry.ACI, entry.PNI, "+"+phone); err != nil {
				// Not fatal, and never returned: one unstorable recipient must
				// not roll back the whole address book.
				c.log(LogWarning, "failed to store discovered recipient: "+err.Error())
			}
			hits = append(hits, sgHit{id: id, phone: phone})
			found++
		}
		return nil
	}
	// signalmeow's own helper, not a bare DoTxn: it takes the same contact lock
	// every other batch write to the recipient table takes.
	if err := device.DoContactTxn(ctx, store); err != nil {
		return sgUpstream(err)
	}

	c.mu.Lock()
	for _, hit := range hits {
		c.known[hit.id] = true
	}
	c.mu.Unlock()
	for _, hit := range hits {
		// Name is left empty: the address book already has one for this number,
		// and the app prefers its own contact name over anything a service
		// reports. Sending a blank one here would overwrite it.
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

// publishSelfContact gives our own account a contact row.
//
// WhatsApp and Telegram both hand one over, which is how a note to self ends up
// titled "<name> (WhatsApp)". Signal announces nothing about the account to
// itself, so without this the self chat had no name and fell back to showing a
// bare ACI.
func (c *sgConn) publishSelfContact() {
	c.mu.Lock()
	client, device := c.client, c.device
	c.mu.Unlock()
	if client == nil || device == nil {
		return
	}
	// The account record, first: it is the account's own name as the user set
	// it, and it survives this app minting a new profile key at registration —
	// which is exactly what makes the profile fetch below come back nameless.
	// It only exists after a storage sync, so this is published again once one
	// has run.
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
		// A number is a poor label, but it is the account and it is readable —
		// unlike the bare ACI the row falls back to with nothing at all.
		name = device.Number
	}
	c.listener.OnContact(SignalSelfID(), name, strings.TrimPrefix(device.Number, "+"), true, false, true)
}

// Signal keeps formatting apart from the text: the body travels plain and a
// BodyRange marks each styled span, in UTF-16 units. The app stores WhatsApp's
// `*bold*` / `_italic_` markers in the text itself (see Markup.kt), so the two
// are translated here at Signal's edge, the way Tg.kt does it for Telegram.
// Sent without this, the markers reached the other side as literal asterisks
// and underscores.

// sgStyleRanges reads the spec Kotlin builds from Markup.parse:
// "start,length,b" runs joined by ';', offsets already in UTF-16 units.
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

// sgWithMarkers puts the markers back into a body that arrived with its styling
// apart. Mirrors Markup.withMarkers, including its rule about which runs can be
// written as markers at all: one that starts inside a word or is padded with
// spaces would render as literal punctuation rather than styling, so it is left
// plain instead.
func sgWithMarkers(body string, ranges []*signalpb.BodyRange) string {
	if body == "" || len(ranges) == 0 {
		return body
	}
	// UTF-16 throughout, because that is what the offsets count.
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
			// Spoiler, strikethrough and monospace have no marker the app
			// stores, so the text stays plain rather than gaining a stray one.
			continue
		}
		start, end := int(r.GetStart()), int(r.GetStart()+r.GetLength())
		if start < 0 || end > len(units) || end <= start {
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
		// A run ending where another does must close from the inside out.
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

func isWordUnit(u uint16) bool {
	r := rune(u)
	return unicode.IsLetter(r) || unicode.IsDigit(r)
}

// sgEchoOwn writes a message we just sent into the app's own store.
//
// Signal never delivers a message back to the device that sent it — the sync
// message goes to the account's OTHER devices only. Without this the bubble
// never appeared and the chat looked like nothing had been sent. WhatsApp does
// the same thing (see echoLocalMedia).
func sgEchoOwn(c *sgConn, chatId, msgID, text, msgType, fileID string, timeSent int64, quotedId, quotedText string) {
	c.listener.OnMessage(
		chatId, msgID, SignalSelfID(), text, true, timeSent, false,
		msgType, fileID, 0, 0, false, false, quotedId, quotedText, "", "", false,
	)
	c.listener.OnChat(chatId, "", 0, false, timeSent)
}

// --- Attachments -----------------------------------------------------------

// sgAttachmentKind maps a MIME type onto the msgType strings the app already
// uses for WhatsApp and Telegram, so a Signal photo renders through the same
// bubble as any other photo.
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

// sgFileID packs an attachment pointer into the opaque token the app stores on
// a message row and hands back to start a download later, the same shape the
// WhatsApp side uses.
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

// SignalSendAttachment uploads a file and sends it. voiceNote only matters for
// audio: a voice note renders as a playable waveform rather than a file, and
// Signal decides that from the flag, not the MIME type.
func SignalSendAttachment(
	chatId string, path string, caption string, mime string, voiceNote bool,
) string {
	c, client, _ := sgActive()
	if client == nil {
		return ""
	}

	body, err := os.ReadFile(path)
	if err != nil {
		c.log(LogError, "attachment read failed: "+err.Error())
		return ""
	}

	ctx := context.TODO()
	ptr, err := client.UploadAttachment(ctx, body)
	if err != nil {
		c.log(LogError, "attachment upload failed: "+err.Error())
		return ""
	}
	ptr.ContentType = proto.String(mime)
	ptr.FileName = proto.String(filepath.Base(path))
	if voiceNote {
		// The flag is what makes Signal render a waveform instead of a file
		// attachment; the MIME type alone is not enough.
		flags := uint32(signalpb.AttachmentPointer_VOICE_MESSAGE)
		ptr.Flags = &flags
	}

	timestamp := uint64(time.Now().UnixMilli())
	dm := &signalpb.DataMessage{
		Timestamp:   &timestamp,
		Attachments: []*signalpb.AttachmentPointer{ptr},
	}
	if caption != "" {
		dm.Body = proto.String(caption)
	}
	if err := sgSend(c, client, chatId, &signalpb.Content{
		Content: &signalpb.Content_DataMessage{DataMessage: dm},
	}); err != nil {
		c.log(LogError, "attachment send failed: "+err.Error())
		return ""
	}
	msgID := fmt.Sprintf("%d", timestamp)
	sgEchoOwn(c, chatId, msgID, caption, sgAttachmentKind(mime, voiceNote), sgFileID(ptr), int64(timestamp/1000), "", "")
	// The file is already on this device, so hand the local copy straight over
	// rather than making the bubble fetch back what we just uploaded.
	c.listener.OnFileDownloaded(chatId, msgID, path, 2)
	return msgID
}

// SignalDownloadAttachment fetches a received attachment and reports the local
// path through OnFileDownloaded, matching the WhatsApp download contract:
// status 2 is success, 3 is failure.
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

// sgExtFor extends the shared image/video mapping with the audio types only
// Signal sends, rather than forking a second copy of the whole table.
func sgExtFor(mime string) string {
	switch mime {
	case "audio/aac", "audio/mp4", "audio/m4a":
		return ".m4a"
	case "audio/ogg", "audio/ogg; codecs=opus":
		return ".ogg"
	}
	return extFromMime(mime, "")
}

// --- Reactions, edits, deletes, receipts, typing ----------------------------

// sgTargetAuthor is the ACI a reaction or remote-delete points at: the person
// who wrote the message being acted on. In a 1:1 chat that is either us or the
// other party; in a group it has to come from the stored row.
func sgTargetAuthor(chatId, senderId string) string {
	id := senderId
	if id == "" {
		id = chatId
	}
	bare, _ := sgTrimPNI(strings.TrimPrefix(id, SgIDPrefix))
	return bare
}

// SignalReact adds or removes a reaction. An empty emoji removes.
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

// SignalDelete asks everyone to drop a message we sent.
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

// SignalEdit replaces the text of a message we sent. Signal keys the edit by
// the original's timestamp and carries a fresh one for the edit itself.
func SignalEdit(chatId string, msgId string, newText string, styles string) bool {
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
	edit := &signalpb.EditMessage{
		TargetSentTimestamp: &target,
		DataMessage: &signalpb.DataMessage{
			Timestamp:  &now,
			Body:       proto.String(newText),
			BodyRanges: ranges,
		},
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

// SignalMarkRead sends a read receipt for one message.
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

// SignalSetTyping publishes the typing indicator.
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

// sgKeyReadings turns what SVR2 handed back into every key it could plausibly
// be, because the payload is not self-describing and one wrong layer produces a
// key that fails no differently from a wrong PIN.
//
// libsignal's hierarchy (rust/account-keys/src/lib.rs) is
//
//	AccountEntropyPool  --HKDF(info=svrMasterKeyInfo)-->  SvrKey  --HMAC(label)-->  storage key
//
// and only the SvrKey is supposed to be in SVR2. Signal has changed what it
// stores there more than once, though, and the account's records may predate the
// entropy pool entirely, so the pool readings are tried too: the storage
// manifest authenticates the answer, so a wrong guess costs one request.
func sgKeyReadings(candidates [][]byte) []sgKeyReading {
	const svrMasterKeyInfo = "20240801_SIGNAL_SVR_MASTER_KEY"
	out := make([]sgKeyReading, 0, len(candidates)*3)
	for _, raw := range candidates {
		out = append(out, sgKeyReading{name: "svr key", key: raw})
		if derived, err := sgHKDF(raw, svrMasterKeyInfo); err == nil {
			out = append(out, sgKeyReading{name: "entropy pool", key: derived})
		}
		// The pool is 64 characters of [0-9a-z], and 32 bytes written out in hex
		// is exactly that, so a record holding the pool's bytes has to be spelt
		// back out before the derivation sees it.
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

// SignalProbeStorage reports whether the account still has a storage-service
// manifest on the server, and whether our current master key can open it.
//
// This is the question behind "my contacts are missing": the contact list lives
// in storage service, encrypted with a key derived from the account entropy
// pool. Registering minted a new pool, so the old manifest — if it is still
// there — is unreadable by us. If the server has no manifest at all, then
// registering discarded it and no amount of PIN recovery brings it back.
func SignalProbeStorage() string {
	c, client, device := sgActive()
	if client == nil || device == nil {
		return sgErrNotRegistered
	}
	_ = c
	if len(device.MasterKey) == 0 {
		return sgErrNoMasterKey
	}
	// currentVersion 0 asks for whatever the server has.
	update, err := client.FetchStorage(context.TODO(), device.MasterKey, 0, nil)
	if err != nil {
		return sgUpstream(err)
	}
	if update == nil {
		return sgErrNoManifest
	}
	return fmt.Sprintf("manifest version %d, %d records readable, %d missing",
		update.Version, len(update.NewRecords), len(update.MissingRecords))
}

// SignalRestoreFromPIN recovers the account's original master key from SVR2 and
// re-reads the storage service with it, which is where the contact list lives.
//
// Registering minted a fresh master key, so the account's existing storage
// manifest stayed on the server unreadable — contacts the user has talked to
// but who are not discoverable by phone number were invisible. Returns "" on
// success, otherwise an error code for the UI.
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

	// Settle which reading of the payload is the real key by using it: only the
	// right one decrypts the account's storage manifest.
	var masterKey []byte
	var lastErr error
	for _, candidate := range sgKeyReadings(candidates) {
		// update != nil as well: the server answers 204 when it holds no
		// manifest, which is not the same as this key opening one — taking it
		// for success stored whichever reading happened to be tried first.
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
	if err := c.container.PutDevice(ctx, &device.DeviceData); err != nil {
		c.log(LogError, "failed to store recovered key: "+err.Error())
		return sgErrStoreFailed
	}
	// SyncStorage walks the manifest and raises a ContactList event, which is
	// what actually fills the contact table.
	client.SyncStorage(ctx)
	c.log(LogInfo, "master key recovered from SVR2, storage sync started")
	return ""
}

// --- Profile and privacy ----------------------------------------------------

// sgMyProfile is our own published profile, or nil when it cannot be read.
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

// SignalMyName is the account's published display name, or "" if none is set.
func SignalMyName() string {
	if p := sgMyProfile(); p != nil {
		return p.Name
	}
	return ""
}

// SignalMyAbout is the account's "about" line.
func SignalMyAbout() string {
	if p := sgMyProfile(); p != nil {
		return p.About
	}
	return ""
}

// SignalSetProfile publishes name and about together, because Signal's profile
// endpoint replaces every field at once — sending one alone would blank the
// other.
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

// SignalSetDiscoverable controls whether contact discovery can find this
// account by its phone number.
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

// SignalMyPhone is the registered phone number in E.164, for display. Signal
// keys everything by ACI, so the number is not recoverable from the self id.
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

// SignalSendContact sends a contact card. numbers is comma-separated.
func SignalSendContact(chatId string, name string, numbers string) string {
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

	// The whole display name goes in givenName: the app carries one name string,
	// and splitting it on whitespace would guess wrong for most of the world.
	timestamp := uint64(time.Now().UnixMilli())
	dm := &signalpb.DataMessage{
		Timestamp: &timestamp,
		Contact: []*signalpb.DataMessage_Contact{{
			Name:   &signalpb.DataMessage_Contact_Name{GivenName: proto.String(name)},
			Number: phones,
		}},
	}
	if err := sgSend(c, client, chatId, signalmeow.WrapDataMessage(dm)); err != nil {
		c.log(LogError, "contact send failed: "+err.Error())
		return ""
	}

	msgID := fmt.Sprintf("%d", timestamp)
	// The app's contact rows carry "name\nnumber…" as their body, which is what
	// its preview and the Add-contact action read back.
	body := name + "\n" + strings.ReplaceAll(numbers, ",", "\n")
	sgEchoOwn(c, chatId, msgID, body, "contact", "", int64(timestamp/1000), "", "")
	return msgID
}

// SignalLookupNumber resolves one phone number to a Signal chat id, or "" when
// the number has no Signal account (or is not discoverable by number).
// number is E.164 without the leading '+'. Blocking.
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
	// Take the id from the MERGED recipient, not straight from the lookup.
	// Discovery often answers PNI-only, while an existing chat with the same
	// person is keyed by the ACI learned when they messaged us — using the
	// lookup's own answer opened a second, empty chat beside the real one.
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
