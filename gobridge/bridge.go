
package wmbridge

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"google.golang.org/protobuf/proto"

	_ "github.com/mattn/go-sqlite3"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/appstate"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waCompanionReg"
	"go.mau.fi/whatsmeow/proto/waE2E"
	waHistorySync "go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/proto/waMmsRetry"
	"go.mau.fi/whatsmeow/proto/waSyncAction"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
)

// Callbacks arrive on arbitrary Go threads; the app must dispatch to its own
// threads as needed.
type EventListener interface {
	OnStateChanged(state string)
	OnQrCode(code string)
	OnPairCode(code string)
	OnPairError(code string)
	OnContact(id string, name string, phone string, isSelf bool, isGroup bool, isSaved bool)
	OnChat(chatId string, name string, unreadCount int, isArchived bool, lastMessageTime int64)
	OnContactsSynced()
	OnMessage(chatId string, msgId string, senderId string, text string, fromMe bool, timeSent int64, isRead bool, msgType string, fileId string, latitude float64, longitude float64, isHistory bool, isEdited bool, quotedId string, quotedText string, quotedType string, senderName string, isForwarded bool)
	OnMessageDeleted(chatId string, msgId string)
	OnReaction(chatId string, msgId string, senderId string, emoji string)
	OnFileDownloaded(chatId string, msgId string, filePath string, status int)
	OnDownloadProgress(chatId string, msgId string, pct int)
	OnMessageRead(chatId string, msgId string)
	OnMessagePlayed(chatId string, msgId string)
	OnChatReadSelf(chatId string, msgId string)
	OnMessageSendFailed(chatId string, msgId string)
	OnMute(chatId string, muted bool)
	OnChatState(chatId string, userId string, state string)
	OnPresence(userId string, isOnline bool, lastSeen int64)
	OnSyncProgress(progress int)
	OnChatHistoryDelivered(chatId string, count int, forExport bool, oldestId string, oldestTime int64, oldestFromMe bool)
	OnExportMessage(chatId string, msgId string, senderId string, text string, fromMe bool, timeSent int64, msgType string, fileId string, senderName string, isEdited bool)
	OnLog(level int, message string)
}

const (
	LogDebug   = 0
	LogInfo    = 1
	LogWarning = 2
	LogError   = 3
)

type conn struct {
	id int
	// The live client, behind an atomic pointer: resetDevice replaces it after a
	// logout or a remote unlink, while callers on other goroutines (every
	// exported function, the event dispatcher, the login loop) read it. As a
	// plain field that was an unsynchronised read/write of the same word — a
	// data race, and one where a call already in flight kept using the old,
	// poisoned client and failed with ErrDeviceDeleted instead of retrying on
	// the fresh one. Always go through getClient()/setClient().
	clientPtr atomic.Pointer[whatsmeow.Client]
	container *sqlstore.Container
	path      string
	listener  EventListener
	state     string
	timeReads map[string]time.Time
	statePending    []string
	stateDelivering bool
	loginActive     bool
	loginGen int
	qrReady  bool
	// pairing codes die with the login socket (~160s), so the login loop
	// re-requests one for this number on every new socket round
	pendingPairPhone string
	pairSentRound    int
	loginRound       int
	// A history response carrying messages names its chat, but an empty
	// end-of-history sync names no conversation, so it is attributed to this
	// slot. The app keeps at most one on-demand request in flight (enforced
	// app-side), which makes the single slot unambiguous even for empty
	// responses. historyForExport is sticky (not cleared on consume) so a
	// duplicate/late page is still routed away from local storage until the
	// next request changes the mode.
	historyChat      string
	historyForExport bool
	historyActive    bool
	mediaRetries map[string]*pendingMediaRetry
	contactsSyncing bool
}

// A sleeping phone has to receive the push, wake, and re-upload, so this is
// generous; when it elapses the download is failed (status 3) and the pending
// entry dropped, so the bubble never stays stuck "downloading" forever and the
// mediaRetries map can't accumulate entries for requests that go unanswered.
const mediaRetryTimeout = 60 * time.Second

type pendingMediaRetry struct {
	chatId        string
	downloadable  whatsmeow.DownloadableMessage
	setDirectPath func(string)
	ext           string
	total         int64
	timer         *time.Timer
}

// Publish-if-absent: two concurrent DownloadFile calls for the same message
// (the bind-time auto-download vs tap race downloadToPath documents) both pass
// the hasPendingMediaRetry guard, and letting the second overwrite the first
// orphaned a timer that later failed a download the phone's answer had
// already delivered.
func (c *conn) setPendingMediaRetry(msgId string, r *pendingMediaRetry) bool {
	mx.Lock()
	defer mx.Unlock()
	if _, exists := c.mediaRetries[msgId]; exists {
		return false
	}
	c.mediaRetries[msgId] = r
	return true
}

func (c *conn) hasPendingMediaRetry(msgId string) bool {
	mx.Lock()
	defer mx.Unlock()
	_, ok := c.mediaRetries[msgId]
	return ok
}

func (c *conn) takeAllPendingMediaRetries() []*pendingMediaRetry {
	mx.Lock()
	defer mx.Unlock()
	out := make([]*pendingMediaRetry, 0, len(c.mediaRetries))
	for id, r := range c.mediaRetries {
		out = append(out, r)
		delete(c.mediaRetries, id)
	}
	return out
}

func (c *conn) takePendingMediaRetry(msgId string) (*pendingMediaRetry, bool) {
	mx.Lock()
	defer mx.Unlock()
	r, ok := c.mediaRetries[msgId]
	if ok {
		delete(c.mediaRetries, msgId)
	}
	return r, ok
}

func (c *conn) setPendingHistory(chatId string, forExport bool) {
	mx.Lock()
	c.historyChat = chatId
	c.historyForExport = forExport
	c.historyActive = true
	mx.Unlock()
}

func (c *conn) clearPendingActive() {
	mx.Lock()
	c.historyActive = false
	mx.Unlock()
}

func (c *conn) exportRouted(chatId string) bool {
	mx.Lock()
	defer mx.Unlock()
	return c.historyForExport && c.historyChat == chatId
}

func (c *conn) takePendingHistory() (chatId string, forExport bool, ok bool) {
	mx.Lock()
	defer mx.Unlock()
	if !c.historyActive {
		return "", false, false
	}
	c.historyActive = false
	return c.historyChat, c.historyForExport, true
}

var (
	mx         sync.Mutex
	nextConnId int           = 0
	conns      map[int]*conn = make(map[int]*conn)
)

func getConn(connId int) *conn {
	mx.Lock()
	defer mx.Unlock()
	return conns[connId]
}

// Never read clientPtr directly: resetDevice swaps it from another goroutine.
func (c *conn) getClient() *whatsmeow.Client { return c.clientPtr.Load() }

func (c *conn) setClient(client *whatsmeow.Client) { c.clientPtr.Store(client) }

func (c *conn) log(level int, msg string) {
	defer func() { recover() }()
	c.listener.OnLog(level, msg)
}

// Transitions are delivered in the order they were recorded. The callback used
// to be made after releasing mx, so two racing writers (a Kotlin thread calling
// Connect/Logout, whatsmeow's event goroutine, the login loop) could record
// "connecting" then "connected" yet deliver them the other way round. Nothing
// corrects that afterwards: the
// transition is deduplicated against c.state, so no further callback fires
// until the next genuine change, and the app's onStateChanged is
// last-write-wins — the UI sat on the loser indefinitely.
//
// Delivery is done by whichever goroutine finds the queue idle; a writer that
// arrives mid-drain just enqueues, which also keeps a listener that calls back
// into setState from deadlocking.
func (c *conn) setState(state string) {
	mx.Lock()
	if c.state == state {
		mx.Unlock()
		return
	}
	c.state = state
	c.statePending = append(c.statePending, state)
	if c.stateDelivering {
		mx.Unlock()
		return
	}
	c.stateDelivering = true
	for len(c.statePending) > 0 {
		next := c.statePending[0]
		c.statePending = c.statePending[1:]
		mx.Unlock()
		c.listener.OnStateChanged(next)
		mx.Lock()
	}
	c.stateDelivering = false
	mx.Unlock()
}

func (c *conn) getTimeRead(chatId string) time.Time {
	mx.Lock()
	defer mx.Unlock()
	return c.timeReads[chatId]
}

func (c *conn) setTimeRead(chatId string, t time.Time) {
	mx.Lock()
	defer mx.Unlock()
	if t.After(c.timeReads[chatId]) {
		c.timeReads[chatId] = t
	}
}

type bridgeLogger struct {
	c   *conn
	mod string
}

// Debugf is intentionally a no-op: whatsmeow logs every sent/received node
// at debug level, and formatting + crossing the gomobile boundary for each
// one is pure overhead in production. The bridge's own debug lines go
// through c.log(LogDebug, ...) directly and are unaffected.
func (l *bridgeLogger) Debugf(msg string, args ...interface{}) {}
func (l *bridgeLogger) Infof(msg string, args ...interface{}) {
	l.c.log(LogInfo, l.mod+": "+fmt.Sprintf(msg, args...))
}
func (l *bridgeLogger) Warnf(msg string, args ...interface{}) {
	l.c.log(LogWarning, l.mod+": "+fmt.Sprintf(msg, args...))
}
func (l *bridgeLogger) Errorf(msg string, args ...interface{}) {
	l.c.log(LogError, l.mod+": "+fmt.Sprintf(msg, args...))
}
func (l *bridgeLogger) Sub(mod string) waLog.Logger {
	return &bridgeLogger{c: l.c, mod: l.mod + "/" + mod}
}

func Init(dataDir string, listener EventListener) int {
	c := &conn{
		path:         dataDir,
		listener:     listener,
		state:        "disconnected",
		timeReads:    make(map[string]time.Time),
		mediaRetries: make(map[string]*pendingMediaRetry),
	}

	if err := os.MkdirAll(dataDir+"/avatars", os.ModePerm); err != nil {
		c.log(LogError, fmt.Sprintf("mkdir error %v", err))
		return -1
	}

	ctx := context.TODO()
	dbLog := &bridgeLogger{c: c, mod: "db"}
	sessionPath := dataDir + "/session.db"
	sqlAddress := fmt.Sprintf("file:%s?_foreign_keys=on", sessionPath)
	container, err := sqlstore.New(ctx, "sqlite3", sqlAddress, dbLog)
	if err != nil {
		c.log(LogError, fmt.Sprintf("sqlite error %v", err))
		return -1
	}

	deviceStore, err := container.GetFirstDevice(ctx)
	if err != nil {
		c.log(LogError, fmt.Sprintf("dev store error %v", err))
		return -1
	}

	store.DeviceProps.RequireFullSync = proto.Bool(false)
	store.DeviceProps.HistorySyncConfig.SupportCallLogHistory = proto.Bool(false)
	store.DeviceProps.HistorySyncConfig.SupportRecentSyncChunkMessageCountTuning = proto.Bool(true)
	store.DeviceProps.HistorySyncConfig.OnDemandReady = proto.Bool(true)
	store.DeviceProps.PlatformType = waCompanionReg.DeviceProps_FIREFOX.Enum()
	store.DeviceProps.Os = proto.String("Linux")

	c.container = container

	mx.Lock()
	connId := nextConnId
	nextConnId++
	c.id = connId
	conns[connId] = c
	mx.Unlock()

	client := newDeviceClient(connId, c, deviceStore)
	if client == nil {
		c.log(LogError, "client error")
		mx.Lock()
		delete(conns, connId)
		mx.Unlock()
		return -1
	}
	c.setClient(client)
	// off the caller's thread: Init already blocks the first Activity behind the
	// store migrations, and this only reclaims disk
	go sweepPartialMedia(c)
	return connId
}

func newDeviceClient(connId int, c *conn, deviceStore *store.Device) *whatsmeow.Client {
	clientLog := &bridgeLogger{c: c, mod: "client"}
	client := whatsmeow.NewClient(deviceStore, clientLog)
	if client != nil {
		client.AddEventHandler(func(evt interface{}) { handleEvent(connId, c, evt) })
	}
	return client
}

func HasSession(connId int) bool {
	c := getConn(connId)
	return c != nil && c.getClient().Store.ID != nil
}

func Connect(connId int) bool {
	c := getConn(connId)
	if c == nil {
		return false
	}
	c.setState("connecting")
	if err := c.getClient().Connect(); err != nil {
		c.log(LogError, fmt.Sprintf("connect failed %v", err))
		c.setState("disconnected")
		return false
	}
	return true
}

func Disconnect(connId int) {
	c := getConn(connId)
	if c == nil {
		return
	}
	c.getClient().Disconnect()
	c.setState("disconnected")
}

func StartLogin(connId int) bool {
	c := getConn(connId)
	if c == nil {
		return false
	}

	mx.Lock()
	if c.loginActive {
		mx.Unlock()
		return true
	}
	c.loginActive = true
	c.loginGen++
	gen := c.loginGen
	mx.Unlock()

	go loginLoop(c, gen)
	return true
}

func StopLogin(connId int) {
	c := getConn(connId)
	if c == nil {
		return
	}
	mx.Lock()
	active := c.loginActive
	c.loginActive = false
	mx.Unlock()
	if active && c.getClient().Store.ID == nil {
		c.getClient().Disconnect()
	}
}

func (c *conn) beginLoginRound(gen int, round int) bool {
	mx.Lock()
	defer mx.Unlock()
	if !c.loginActive || c.loginGen != gen {
		return false
	}
	c.loginRound = round
	return true
}

func (c *conn) setLoginQrReady(gen int, ready bool) {
	mx.Lock()
	if c.loginGen == gen {
		c.qrReady = ready
	}
	mx.Unlock()
}

func (c *conn) isQrReady() bool {
	mx.Lock()
	defer mx.Unlock()
	return c.qrReady
}

func loginLoop(c *conn, gen int) {
	defer func() {
		mx.Lock()
		if c.loginGen == gen {
			c.loginActive = false
			c.qrReady = false
		}
		mx.Unlock()
	}()

	for round := 1; round <= 20; round++ {
		if !c.beginLoginRound(gen, round) {
			return
		}
		c.setState("connecting")

		ch, err := c.getClient().GetQRChannel(context.Background())
		if err != nil {
			if errors.Is(err, whatsmeow.ErrQRStoreContainsID) {
				_ = c.getClient().Connect()
				return
			}
			c.log(LogError, fmt.Sprintf("qr channel error %v", err))
			c.setState("disconnected")
			return
		}

		if err := c.getClient().Connect(); err != nil {
			c.log(LogError, fmt.Sprintf("connect failed %v", err))
			c.setState("disconnected")
			return
		}

		timedOut := false
		for evt := range ch {
			switch evt.Event {
			case whatsmeow.QRChannelEventCode:
				c.setLoginQrReady(gen, true)
				c.listener.OnQrCode(evt.Code)
				if phone, round := c.pairRequestDue(); phone != "" {
					go sendPairCode(c, phone, round)
				}
			case whatsmeow.QRChannelSuccess.Event:
				c.log(LogInfo, "qr channel success")
				c.clearPendingPair()
				return
			case whatsmeow.QRChannelClientOutdated.Event:
				c.setState("outdated")
				return
			case whatsmeow.QRChannelTimeout.Event:
				c.log(LogWarning, "qr timeout, restarting login flow")
				timedOut = true
			case whatsmeow.QRChannelEventError:
				c.log(LogError, fmt.Sprintf("qr channel error %v", evt.Error))
				c.setState("disconnected")
				return
			default:
				// NOT fatal: several QR-channel events are informational and
				// leave the channel open (the passkey-pairing ones already exist
				// upstream). Treating any unknown event as a terminal failure
				// aborted login with a bare "Disconnected" and abandoned a still
				// live socket. Log it and keep reading.
				c.log(LogWarning, "qr channel event (ignored) "+evt.Event)
			}
		}

		c.setLoginQrReady(gen, false)
		if !timedOut {
			return
		}
		c.getClient().Disconnect()
		time.Sleep(1 * time.Second)
	}
	c.setState("disconnected")
}

func (c *conn) pairRequestDue() (string, int) {
	mx.Lock()
	defer mx.Unlock()
	if c.pendingPairPhone == "" || c.pairSentRound == c.loginRound {
		return "", 0
	}
	c.pairSentRound = c.loginRound
	return c.pendingPairPhone, c.loginRound
}

func (c *conn) clearPendingPair() {
	mx.Lock()
	c.pendingPairPhone = ""
	mx.Unlock()
}

func sendPairCode(c *conn, phoneNumber string, round int) bool {
	pairCode, err := c.getClient().PairPhone(context.TODO(), phoneNumber, true,
		whatsmeow.PairClientFirefox, "Firefox (Linux)")
	if err != nil {
		c.log(LogError, fmt.Sprintf("pair phone error %v", err))
		mx.Lock()
		if c.pairSentRound == round {
			c.pairSentRound = 0
		}
		mx.Unlock()
		c.listener.OnPairError(pairErrorCode(err))
		return false
	}
	c.listener.OnPairCode(pairCode)
	return true
}

func RequestPairCode(connId int, phoneNumber string) bool {
	c := getConn(connId)
	if c == nil {
		return false
	}

	if len(phoneNumber) <= 6 {
		c.listener.OnPairError(pairErrorCode(whatsmeow.ErrPhoneNumberTooShort))
		return false
	}
	if strings.HasPrefix(phoneNumber, "0") {
		c.listener.OnPairError(pairErrorCode(whatsmeow.ErrPhoneNumberIsNotInternational))
		return false
	}

	mx.Lock()
	c.pendingPairPhone = phoneNumber
	c.pairSentRound = 0
	mx.Unlock()

	if !c.getClient().IsConnected() || !c.isQrReady() {
		StartLogin(c.id)
		for i := 0; i < 150; i++ {
			if c.isQrReady() && c.getClient().IsConnected() {
				break
			}
			time.Sleep(100 * time.Millisecond)
		}
	}
	if !c.getClient().IsConnected() {
		c.listener.OnPairError("notconnected")
		return false
	}

	phone, round := c.pairRequestDue()
	if phone == "" {
		return true
	}
	return sendPairCode(c, phone, round)
}

func pairErrorCode(err error) string {
	switch {
	case errors.Is(err, whatsmeow.ErrPhoneNumberTooShort):
		return "short"
	case errors.Is(err, whatsmeow.ErrPhoneNumberIsNotInternational):
		return "international"
	case errors.Is(err, whatsmeow.ErrNotConnected):
		return "notconnected"
	default:
		return "other:" + err.Error()
	}
}

func Logout(connId int) {
	c := getConn(connId)
	if c == nil {
		return
	}
	ctx := context.TODO()
	if err := c.getClient().Logout(ctx); err != nil {
		c.log(LogWarning, fmt.Sprintf("logout error %v", err))
		// whatsmeow skips BOTH the disconnect and the local store wipe when the
		// server logout fails (needs a live authenticated socket to send the
		// unlink request; fails when offline or mid-reconnect). That would leave
		// the device still paired locally, so HasSession stays true and the app
		// bounces back to the chat list on a dead session instead of the login
		// screen. Force the local cleanup so a logout always takes effect here.
		c.getClient().Disconnect()
		if err := c.getClient().Store.Delete(ctx); err != nil {
			c.log(LogError, fmt.Sprintf("store delete after failed logout %v", err))
		}
	}
	ok := c.resetDevice()
	// logged_out first either way: the account really is gone, and that is what
	// moves the app off the chat list. StateStoreBroken then says the client
	// could not be rebuilt, so the login screen explains the dead QR instead of
	// showing one that can never succeed.
	c.setState("logged_out")
	if !ok {
		c.setState(StateStoreBroken)
	}
}

// Kotlin matches on this exact word (LoginActivity).
const StateStoreBroken = "store_broken"

// A logout — ours or a remote unlink (events.LoggedOut) — poisons the device's
// in-memory stores, so the old client can never pair again (Connect fails with
// ErrDeviceDeleted). Rebuilding here makes a re-login work without restarting
// the app. The per-session caches otherwise leak into the next account: stale
// read watermarks marked its incoming messages already-read, and an armed
// media-retry timer fired into the new session.
func (c *conn) resetDevice() bool {
	ctx := context.TODO()
	c.getClient().Disconnect()
	for _, pending := range c.takeAllPendingMediaRetries() {
		if pending.timer != nil {
			pending.timer.Stop()
		}
	}
	mx.Lock()
	c.timeReads = map[string]time.Time{}
	c.historyChat = ""
	c.historyActive = false
	c.historyForExport = false
	mx.Unlock()
	// Answers whether the client really was replaced. Both failures used to
	// return quietly, leaving the poisoned client in place while Logout went on
	// to report "logged_out": the app moved to the login screen and every QR
	// round after that failed with ErrDeviceDeleted for the rest of the
	// process, with nothing but a log line to say why.
	deviceStore, err := c.container.GetFirstDevice(ctx)
	if err != nil {
		c.log(LogError, fmt.Sprintf("dev store error after logout %v", err))
		return false
	}
	client := newDeviceClient(c.id, c, deviceStore)
	if client == nil {
		c.log(LogError, "could not rebuild the device client after logout")
		return false
	}
	c.setClient(client)
	return true
}

func requestContactsAsync(connId int) {
	c := getConn(connId)
	if c == nil {
		return
	}
	mx.Lock()
	if c.contactsSyncing {
		mx.Unlock()
		return
	}
	c.contactsSyncing = true
	mx.Unlock()
	go func() {
		defer func() {
			mx.Lock()
			c.contactsSyncing = false
			mx.Unlock()
		}()
		requestContacts(connId)
	}()
}

func GetSelfId(connId int) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	client := c.getClient()
	if client.Store.ID == nil {
		return ""
	}
	return strFromJid(*client.Store.ID)
}

func sendWithEcho(c *conn, chatJid types.JID, message *waE2E.Message, what string) string {
	msgID := c.getClient().GenerateMessageID()
	echoLocal(c, chatJid, msgID, message)
	resp, err := c.getClient().SendMessage(context.Background(), chatJid, message,
		whatsmeow.SendRequestExtra{ID: msgID})
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("%s error %v", what, err))
		failEcho(c, chatJid, msgID)
		return ""
	}
	echoSentMessage(c, chatJid, resp, message)
	return resp.ID
}

func SendTextMessage(connId int, chatId string, text string, mentionedIds string) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	chatJid, err := types.ParseJID(chatId)
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("jid error %v", err))
		return ""
	}
	message := &waE2E.Message{Conversation: &text}
	// The @digits in the body are only decoration: what actually notifies the
	// mentioned person is MentionedJID, and Conversation cannot carry a
	// ContextInfo at all — so a mention forces the extended shape.
	if mentions := splitIds(mentionedIds); len(mentions) > 0 {
		message = &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text:        proto.String(text),
			ContextInfo: &waE2E.ContextInfo{MentionedJID: mentions},
		}}
	}
	return sendWithEcho(c, chatJid, message, "send")
}

func splitIds(ids string) []string {
	out := []string{}
	for _, id := range strings.Split(ids, ",") {
		if id = strings.TrimSpace(id); id != "" {
			out = append(out, id)
		}
	}
	return out
}

// One member per line, "<chat id>\t<mention jid>". They differ: a group
// addresses its members by LID, which is what a mention's digits must match,
// while a chat with that person belongs under their phone number — opening a
// @lid chat would fork a second thread for someone already listed there.
func GetGroupMembers(connId int, chatId string) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	groupJid, err := types.ParseJID(chatId)
	if err != nil || groupJid.Server != types.GroupServer {
		return ""
	}
	info, err := c.getClient().GetGroupInfo(context.Background(), groupJid)
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("group info error %v", err))
		return ""
	}
	var b strings.Builder
	for _, p := range info.Participants {
		if p.Error != 0 || p.JID.IsEmpty() {
			continue
		}
		chatJid := p.JID
		if !p.PhoneNumber.IsEmpty() {
			chatJid = p.PhoneNumber
		}
		b.WriteString(strFromJid(chatJid))
		b.WriteString("\t")
		b.WriteString(strFromJid(p.JID))
		b.WriteString("\n")
	}
	return b.String()
}

func SendLocation(connId int, chatId string, latitude float64, longitude float64) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	chatJid, err := types.ParseJID(chatId)
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("jid error %v", err))
		return ""
	}
	message := waE2E.Message{LocationMessage: &waE2E.LocationMessage{
		DegreesLatitude:  proto.Float64(latitude),
		DegreesLongitude: proto.Float64(longitude),
	}}
	return sendWithEcho(c, chatJid, &message, "send location")
}

func SendContactMessage(connId int, chatId string, displayName string, vcard string) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	chatJid, err := types.ParseJID(chatId)
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("jid error %v", err))
		return ""
	}
	message := waE2E.Message{ContactMessage: &waE2E.ContactMessage{
		DisplayName: proto.String(displayName),
		Vcard:       proto.String(vcard),
	}}
	return sendWithEcho(c, chatJid, &message, "send contact")
}

func SendTextReply(connId int, chatId string, text string, quotedId string, quotedText string, quotedSender string, mentionedIds string) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	chatJid, err := types.ParseJID(chatId)
	if err != nil {
		return ""
	}
	ctxInfo := buildQuoteContext(quotedId, quotedText, quotedSender)
	if mentions := splitIds(mentionedIds); len(mentions) > 0 {
		if ctxInfo == nil {
			ctxInfo = &waE2E.ContextInfo{}
		}
		ctxInfo.MentionedJID = mentions
	}
	message := waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text:        proto.String(text),
			ContextInfo: ctxInfo,
		},
	}
	return sendWithEcho(c, chatJid, &message, "send reply")
}

func SendReaction(connId int, chatId string, msgId string, msgSenderId string, msgFromMe bool, emoji string) bool {
	c := getConn(connId)
	if c == nil {
		return false
	}
	client := c.getClient()
	if client.Store.ID == nil {
		return false
	}
	chatJid, err := types.ParseJID(chatId)
	if err != nil {
		return false
	}
	self := *client.Store.ID
	senderJid := self
	if !msgFromMe {
		senderJid, err = types.ParseJID(msgSenderId)
		if err != nil {
			return false
		}
	}
	message := client.BuildReaction(chatJid, senderJid, msgId, emoji)
	if _, err = client.SendMessage(context.Background(), chatJid, message); err != nil {
		c.log(LogWarning, fmt.Sprintf("send reaction error %v", err))
		return false
	}
	c.listener.OnReaction(chatId, msgId, strFromJid(self), emoji)
	return true
}

func EditWindowSeconds() int64 {
	return int64(whatsmeow.EditWindow / time.Second)
}

func EditMessage(connId int, chatId string, msgId string, newText string, origTimeSent int64) bool {
	c := getConn(connId)
	if c == nil {
		return false
	}
	if origTimeSent > 0 && time.Now().Unix()-origTimeSent > int64(whatsmeow.EditWindow/time.Second) {
		c.log(LogWarning, "edit rejected: outside the protocol edit window")
		return false
	}
	chatJid, err := types.ParseJID(chatId)
	if err != nil {
		return false
	}
	message := waE2E.Message{Conversation: proto.String(newText)}
	_, err = c.getClient().SendMessage(context.Background(), chatJid, c.getClient().BuildEdit(chatJid, msgId, &message))
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("edit message error %v", err))
		return false
	}
	senderId := ""
	if c.getClient().Store.ID != nil {
		senderId = strFromJid(*c.getClient().Store.ID)
	}
	// Update locally, keeping the original timestamp/order (timeSent 0).
	// isRead is FALSE, not true: for an outgoing message it means "the peer read
	// it", and the app's upsert raises is_read monotonically — passing true made
	// editing your own unread message permanently show the read double-tick, with
	// no later receipt able to correct it. false cannot downgrade a real receipt.
	c.listener.OnMessage(chatId, msgId, senderId, newText, true, 0, false, "", "", 0, 0, false, true, "", "", "", "", false)
	return true
}

func DeleteMessageForEveryone(connId int, chatId string, msgId string) bool {
	c := getConn(connId)
	if c == nil {
		return false
	}
	chatJid, err := types.ParseJID(chatId)
	if err != nil {
		return false
	}
	var senderJid types.JID
	if c.getClient().Store.ID != nil {
		senderJid = *c.getClient().Store.ID
	}
	_, err = c.getClient().SendMessage(context.Background(), chatJid, c.getClient().BuildRevoke(chatJid, senderJid, msgId))
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("revoke message error %v", err))
		return false
	}
	c.listener.OnMessageDeleted(chatId, msgId)
	return true
}

func echoSentMessage(c *conn, chatJid types.JID, resp whatsmeow.SendResponse, message *waE2E.Message) {
	echoMessage(c, chatJid, resp.ID, resp.Timestamp, message)
}

func echoLocal(c *conn, chatJid types.JID, msgID string, message *waE2E.Message) {
	echoMessage(c, chatJid, msgID, time.Now(), message)
}

func echoMessage(c *conn, chatJid types.JID, msgID string, ts time.Time, message *waE2E.Message) {
	var messageInfo types.MessageInfo
	messageInfo.Chat = chatJid
	messageInfo.IsFromMe = true
	if c.getClient().Store.ID != nil {
		messageInfo.Sender = *c.getClient().Store.ID
	}
	messageInfo.ID = msgID
	messageInfo.Timestamp = ts
	handleMessageFull(c, messageInfo, message, false, false, false, false, true)
}

func echoLocalMedia(c *conn, chatJid types.JID, msgID string, text string, msgType string,
	localPath string, quotedId string, quotedText string) {
	chatId := getChatId(c.getClient(), &chatJid, nil)
	senderId := ""
	if c.getClient().Store.ID != nil {
		senderId = strFromJid(*c.getClient().Store.ID)
	}
	c.listener.OnMessage(chatId, msgID, senderId, text, true, time.Now().Unix(), c.isSelfChat(chatId),
		msgType, "", 0, 0, false, false, quotedId, quotedText, "", "", false)
	if localPath != "" {
		c.listener.OnFileDownloaded(chatId, msgID, localPath, 2)
	}
}

// The echo is kept and flagged rather than deleted: a send that failed used to
// take its bubble away with it, so the message the user wrote was simply gone.
func failEcho(c *conn, chatJid types.JID, msgID string) {
	c.listener.OnMessageSendFailed(getChatId(c.getClient(), &chatJid, nil), msgID)
}

func buildQuoteContext(quotedId string, quotedText string, quotedSender string) *waE2E.ContextInfo {
	if quotedId == "" {
		return nil
	}
	quotedSender = strings.Replace(quotedSender, "@c.us", "@s.whatsapp.net", 1)
	return &waE2E.ContextInfo{
		StanzaID:      proto.String(quotedId),
		Participant:   proto.String(quotedSender),
		QuotedMessage: &waE2E.Message{Conversation: proto.String(quotedText)},
	}
}

func sendMedia(c *conn, chatId string, filePath string, echoText string, msgType string,
	quotedId string, quotedText string, mediaType whatsmeow.MediaType,
	build func(uploaded whatsmeow.UploadResponse) (*waE2E.Message, string)) string {
	client := c.getClient()
	chatJid, err := types.ParseJID(chatId)
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("jid error %v", err))
		return ""
	}
	msgID := client.GenerateMessageID()
	echoLocalMedia(c, chatJid, msgID, echoText, msgType, filePath, quotedId, quotedText)

	uploaded, err := uploadFile(c, client, msgID, filePath, mediaType)
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("upload error %v", err))
		failEcho(c, chatJid, msgID)
		return ""
	}
	message, ext := build(uploaded)
	resp, err := client.SendMessage(context.Background(), chatJid, message,
		whatsmeow.SendRequestExtra{ID: msgID})
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("send %s error %v", msgType, err))
		failEcho(c, chatJid, msgID)
		return ""
	}
	echoSentMessage(c, chatJid, resp, message)
	finishMediaSend(c, chatJid, resp.ID, ext, filePath)
	return resp.ID
}

// The plain Upload takes the whole plaintext as a []byte and allocates a
// second, encrypted copy of it, so an outgoing video or document — picked by
// the user, with no size limit anywhere in the path — cost about twice its size
// in the heap of a mobile process, held until the send completed. UploadReader
// streams both passes through a scratch file instead; it is named like
// downloadToPath's temp files so sweepPartialMedia reclaims it if the process
// is killed mid-send.
func uploadFile(c *conn, client *whatsmeow.Client, msgId string, filePath string,
	mediaType whatsmeow.MediaType) (whatsmeow.UploadResponse, error) {
	var resp whatsmeow.UploadResponse
	src, err := os.Open(filePath)
	if err != nil {
		return resp, err
	}
	defer src.Close()
	base, err := mediaPath(c, msgId, ".upload")
	if err != nil {
		return resp, err
	}
	tmpPath := fmt.Sprintf("%s.part%d", base, time.Now().UnixNano())
	tmp, err := os.Create(tmpPath)
	if err != nil {
		return resp, err
	}
	defer func() {
		tmp.Close()
		os.Remove(tmpPath)
	}()
	return client.UploadReader(context.Background(), src, tmp, mediaType)
}

// The wrapper, not only the inner bit: an official client sends a view-once
// photo as a ViewOnceMessageV2 (a voice note as the V2Extension), which is why
// the receive path above had to learn that a wrapped voice note carries no
// ViewOnce bit of its own. whatsmeow's send path unwraps all three to derive
// the stanza's type, so handing it a wrapped message is supported.
func wrapViewOnce(msg *waE2E.Message, extension bool) *waE2E.Message {
	if extension {
		return &waE2E.Message{
			ViewOnceMessageV2Extension: &waE2E.FutureProofMessage{Message: msg},
		}
	}
	return &waE2E.Message{ViewOnceMessageV2: &waE2E.FutureProofMessage{Message: msg}}
}

func SendImageMessage(connId int, chatId string, filePath string, caption string, quotedId string, quotedText string, quotedSender string, viewOnce bool) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	return sendMedia(c, chatId, filePath, caption, "image", quotedId, quotedText, whatsmeow.MediaImage,
		func(uploaded whatsmeow.UploadResponse) (*waE2E.Message, string) {
			mimeType := "image/jpeg"
			lower := strings.ToLower(filePath)
			if strings.HasSuffix(lower, ".png") {
				mimeType = "image/png"
			} else if strings.HasSuffix(lower, ".webp") {
				mimeType = "image/webp"
			}
			imageMessage := waE2E.ImageMessage{
				URL:           proto.String(uploaded.URL),
				DirectPath:    proto.String(uploaded.DirectPath),
				MediaKey:      uploaded.MediaKey,
				Mimetype:      proto.String(mimeType),
				FileEncSHA256: uploaded.FileEncSHA256,
				FileSHA256:    uploaded.FileSHA256,
				FileLength:    proto.Uint64(uploaded.FileLength),
			}
			if len(caption) > 0 {
				imageMessage.Caption = proto.String(caption)
			}
			if viewOnce {
				imageMessage.ViewOnce = proto.Bool(true)
			}
			imageMessage.ContextInfo = buildQuoteContext(quotedId, quotedText, quotedSender)
			out := &waE2E.Message{ImageMessage: &imageMessage}
			if viewOnce {
				out = wrapViewOnce(out, false)
			}
			return out, extFromMime(mimeType, ".jpg")
		})
}

func SendVideoMessage(connId int, chatId string, filePath string, caption string, quotedId string, quotedText string, quotedSender string, viewOnce bool) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	return sendMedia(c, chatId, filePath, caption, "video", quotedId, quotedText, whatsmeow.MediaVideo,
		func(uploaded whatsmeow.UploadResponse) (*waE2E.Message, string) {
			mimeType := "video/mp4"
			if strings.HasSuffix(strings.ToLower(filePath), ".3gp") {
				mimeType = "video/3gpp"
			}
			videoMessage := waE2E.VideoMessage{
				URL:           proto.String(uploaded.URL),
				DirectPath:    proto.String(uploaded.DirectPath),
				MediaKey:      uploaded.MediaKey,
				Mimetype:      proto.String(mimeType),
				FileEncSHA256: uploaded.FileEncSHA256,
				FileSHA256:    uploaded.FileSHA256,
				FileLength:    proto.Uint64(uploaded.FileLength),
			}
			if len(caption) > 0 {
				videoMessage.Caption = proto.String(caption)
			}
			if viewOnce {
				videoMessage.ViewOnce = proto.Bool(true)
			}
			videoMessage.ContextInfo = buildQuoteContext(quotedId, quotedText, quotedSender)
			out := &waE2E.Message{VideoMessage: &videoMessage}
			if viewOnce {
				out = wrapViewOnce(out, false)
			}
			return out, extFromMime(mimeType, ".mp4")
		})
}

// The app deletes the staged source once the send returns, so the echo's path
// must be swapped for a permanent copy. If the copy fails, the file state is
// reset instead so the bubble re-downloads via the fileId recorded by
// echoSentMessage rather than pointing forever at the soon-deleted staging file.
func finishMediaSend(c *conn, chatJid types.JID, msgId string, ext string, srcPath string) {
	chatId := getChatId(c.getClient(), &chatJid, nil)
	if localPath := copyToMedia(c, msgId, ext, srcPath); localPath != "" {
		c.listener.OnFileDownloaded(chatId, msgId, localPath, 2)
	} else {
		c.listener.OnFileDownloaded(chatId, msgId, "", 0)
	}
}

func SendAudioMessage(connId int, chatId string, filePath string, durationSeconds int, quotedId string, quotedText string, quotedSender string, waveform []byte, viewOnce bool) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	return sendMedia(c, chatId, filePath, formatDuration(durationSeconds), "audio", quotedId, quotedText, whatsmeow.MediaAudio,
		func(uploaded whatsmeow.UploadResponse) (*waE2E.Message, string) {
			audioMessage := waE2E.AudioMessage{
				URL:           proto.String(uploaded.URL),
				DirectPath:    proto.String(uploaded.DirectPath),
				MediaKey:      uploaded.MediaKey,
				Mimetype:      proto.String("audio/ogg; codecs=opus"),
				FileEncSHA256: uploaded.FileEncSHA256,
				FileSHA256:    uploaded.FileSHA256,
				FileLength:    proto.Uint64(uploaded.FileLength),
				Seconds:       proto.Uint32(uint32(durationSeconds)),
				PTT:           proto.Bool(true),
			}
			if len(waveform) > 0 {
				audioMessage.Waveform = waveform
			}
			if viewOnce {
				audioMessage.ViewOnce = proto.Bool(true)
			}
			audioMessage.ContextInfo = buildQuoteContext(quotedId, quotedText, quotedSender)
			out := &waE2E.Message{AudioMessage: &audioMessage}
			if viewOnce {
				out = wrapViewOnce(out, true)
			}
			return out, ".ogg"
		})
}

func SendDocumentMessage(connId int, chatId string, filePath string, fileName string, mimeType string, quotedId string, quotedText string, quotedSender string) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	if fileName == "" {
		fileName = "file"
	}
	if mimeType == "" {
		mimeType = "application/octet-stream"
	}
	return sendMedia(c, chatId, filePath, fileName, "document", quotedId, quotedText, whatsmeow.MediaDocument,
		func(uploaded whatsmeow.UploadResponse) (*waE2E.Message, string) {
			documentMessage := waE2E.DocumentMessage{
				URL:           proto.String(uploaded.URL),
				DirectPath:    proto.String(uploaded.DirectPath),
				MediaKey:      uploaded.MediaKey,
				Mimetype:      proto.String(mimeType),
				FileEncSHA256: uploaded.FileEncSHA256,
				FileSHA256:    uploaded.FileSHA256,
				FileLength:    proto.Uint64(uploaded.FileLength),
				FileName:      proto.String(fileName),
			}
			documentMessage.ContextInfo = buildQuoteContext(quotedId, quotedText, quotedSender)
			return &waE2E.Message{DocumentMessage: &documentMessage}, extFromFileName(fileName, ".bin")
		})
}

// The fileId crosses gomobile and is stored per message row, and DownloadFile
// never reads the thumbnail bytes or the ContextInfo — whose quoted message
// can embed its own thumbnail — so carrying them bloated it several-fold.
func encodeFileId(kind string, m proto.Message) string {
	m = proto.Clone(m)
	switch t := m.(type) {
	case *waE2E.ImageMessage:
		t.JPEGThumbnail, t.ContextInfo = nil, nil
	case *waE2E.VideoMessage:
		t.JPEGThumbnail, t.ContextInfo = nil, nil
	case *waE2E.AudioMessage:
		t.ContextInfo = nil
	case *waE2E.DocumentMessage:
		t.JPEGThumbnail, t.ContextInfo = nil, nil
	case *waE2E.StickerMessage:
		t.PngThumbnail, t.ContextInfo = nil, nil
	}
	raw, err := proto.Marshal(m)
	if err != nil {
		return ""
	}
	return kind + ":" + base64.StdEncoding.EncodeToString(raw)
}

func DownloadFile(connId int, chatId string, msgId string, fileId string, fromMe bool, senderId string) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	fail := func(why string, err error) string {
		c.log(LogWarning, fmt.Sprintf("%s error %v", why, err))
		c.listener.OnFileDownloaded(chatId, msgId, "", 3)
		return ""
	}

	if c.hasPendingMediaRetry(msgId) {
		return ""
	}

	kind, encoded, found := strings.Cut(fileId, ":")
	if !found {
		return fail("file id parse", nil)
	}
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return fail("file id decode", err)
	}

	var downloadable whatsmeow.DownloadableMessage
	var setDirectPath func(string)
	ext := ""
	var total int64
	switch kind {
	case "img":
		img := &waE2E.ImageMessage{}
		if err := proto.Unmarshal(raw, img); err != nil {
			return fail("file id unmarshal", err)
		}
		downloadable = img
		setDirectPath = func(p string) { img.DirectPath = proto.String(p) }
		ext = extFromMime(img.GetMimetype(), ".jpg")
	case "stk":
		stk := &waE2E.StickerMessage{}
		if err := proto.Unmarshal(raw, stk); err != nil {
			return fail("file id unmarshal", err)
		}
		downloadable = stk
		setDirectPath = func(p string) { stk.DirectPath = proto.String(p) }
		ext = extFromMime(stk.GetMimetype(), ".webp")
	case "aud":
		aud := &waE2E.AudioMessage{}
		if err := proto.Unmarshal(raw, aud); err != nil {
			return fail("file id unmarshal", err)
		}
		downloadable = aud
		setDirectPath = func(p string) { aud.DirectPath = proto.String(p) }
		ext = ".ogg"
	case "vid":
		vid := &waE2E.VideoMessage{}
		if err := proto.Unmarshal(raw, vid); err != nil {
			return fail("file id unmarshal", err)
		}
		downloadable = vid
		setDirectPath = func(p string) { vid.DirectPath = proto.String(p) }
		ext = extFromMime(vid.GetMimetype(), ".mp4")
		total = int64(vid.GetFileLength())
	case "doc":
		doc := &waE2E.DocumentMessage{}
		if err := proto.Unmarshal(raw, doc); err != nil {
			return fail("file id unmarshal", err)
		}
		downloadable = doc
		setDirectPath = func(p string) { doc.DirectPath = proto.String(p) }
		ext = extFromFileName(doc.GetFileName(), ".bin")
	default:
		return fail("file id kind "+kind, nil)
	}

	path, err := downloadToPath(c, chatId, msgId, downloadable, ext, total)
	if err == nil {
		c.listener.OnFileDownloaded(chatId, msgId, path, 2)
		return path
	}
	if !isExpiredMediaErr(err) {
		return fail("download", err)
	}

	// media has expired on WhatsApp's servers, which is common for old
	// history media even though the phone still holds it: ask the phone to
	// re-upload it. Its answer arrives later as an *events.MediaRetry,
	// handled by handleMediaRetryEvent.
	chatJid, jerr := types.ParseJID(chatId)
	if jerr != nil {
		return fail("download", err)
	}
	info := &types.MessageInfo{
		MessageSource: types.MessageSource{
			Chat:     chatJid,
			IsFromMe: fromMe,
			IsGroup:  chatJid.Server == types.GroupServer,
		},
		ID: msgId,
	}
	if info.IsGroup {
		if senderJid, serr := types.ParseJID(senderId); serr == nil {
			info.Sender = senderJid
		}
	}
	pending := &pendingMediaRetry{
		chatId:        chatId,
		downloadable:  downloadable,
		setDirectPath: setDirectPath,
		ext:           ext,
		total:         total,
	}
	// Arm the timer BEFORE publishing the entry. It used to be assigned after
	// setPendingMediaRetry, so the field was written without mx while
	// handleMediaRetryEvent read it under mx — a data race whose losing side
	// left a live timer nobody could stop, which then reported a failed download
	// for one that had actually succeeded.
	//
	// Give up if the phone never answers: fail the download so the bubble leaves
	// "downloading" and becomes retryable, and drop the pending entry. (If the
	// answer arrives first, takePendingMediaRetry here finds nothing and this is
	// a no-op; handleMediaRetryEvent stops the timer on success.)
	pending.timer = time.AfterFunc(mediaRetryTimeout, func() {
		if _, ok := c.takePendingMediaRetry(msgId); ok {
			c.log(LogWarning, fmt.Sprintf("media retry timed out: chat=%s msg=%s", chatId, msgId))
			c.listener.OnFileDownloaded(chatId, msgId, "", 3)
		}
	})
	if !c.setPendingMediaRetry(msgId, pending) {
		pending.timer.Stop()
		return ""
	}
	if rerr := c.getClient().SendMediaRetryReceipt(context.Background(), info, downloadable.GetMediaKey()); rerr != nil {
		if p, ok := c.takePendingMediaRetry(msgId); ok && p.timer != nil {
			p.timer.Stop()
		}
		return fail("media retry request", rerr)
	}
	c.log(LogDebug, fmt.Sprintf("media retry requested: chat=%s msg=%s", chatId, msgId))
	return ""
}

func isExpiredMediaErr(err error) bool {
	return errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith403) ||
		errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith404) ||
		errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith410)
}

func downloadToPath(c *conn, chatId string, msgId string, downloadable whatsmeow.DownloadableMessage, ext string, total int64) (string, error) {
	path, err := mediaPath(c, msgId, ext)
	if err != nil {
		return "", err
	}
	// Download into a distinct temp file and rename on success, instead of
	// writing the final path in place. Two concurrent attempts for the same
	// message (a bind-time auto-download and a tap, or a media-retry answer
	// racing a manual retry) derive the SAME deterministic path: in-place they
	// truncated each other's output, and either one's failure cleanup unlinked
	// the other's good file. Rename within the directory is atomic.
	tmp := fmt.Sprintf("%s.part%d", path, time.Now().UnixNano())
	f, err := os.Create(tmp)
	if err != nil {
		return "", err
	}
	pf := &progressFile{f: f, c: c, chatId: chatId, msgId: msgId, total: total}
	err = c.getClient().DownloadToFile(context.Background(), downloadable, pf)
	if cerr := f.Close(); cerr != nil && err == nil {
		err = cerr
	}
	if err != nil {
		os.Remove(tmp)
		return "", err
	}
	if err := os.Rename(tmp, path); err != nil {
		os.Remove(tmp)
		return "", err
	}
	return path, nil
}

func sweepPartialMedia(c *conn) {
	sweepPartials(c.path+"/media", ".part")
	sweepPartials(c.path+"/avatars", ".tmp")
}

func sweepPartials(dir string, marker string) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	cutoff := time.Now().Add(-time.Hour)
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		i := strings.LastIndex(name, marker)
		if i < 0 {
			continue
		}
		suffix := name[i+len(marker):]
		if suffix == "" || strings.TrimLeft(suffix, "0123456789") != "" {
			continue
		}
		if info, ierr := e.Info(); ierr != nil || info.ModTime().After(cutoff) {
			continue
		}
		_ = os.Remove(dir + "/" + name)
	}
}

func formatDuration(seconds int) string {
	return fmt.Sprintf("%d:%02d", seconds/60, seconds%60)
}

func extFromFileName(name string, fallback string) string {
	i := strings.LastIndex(name, ".")
	if i < 0 {
		return fallback
	}
	ext := name[i:]
	if len(ext) > 12 {
		return fallback
	}
	for _, r := range ext[1:] {
		if !((r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9')) {
			return fallback
		}
	}
	if len(ext) == 1 {
		return fallback
	}
	return strings.ToLower(ext)
}

func extFromMime(mimeType string, fallback string) string {
	switch mimeType {
	case "image/png":
		return ".png"
	case "image/webp":
		return ".webp"
	case "image/gif":
		return ".gif"
	case "image/jpeg":
		return ".jpg"
	case "video/mp4":
		return ".mp4"
	case "video/3gpp":
		return ".3gp"
	}
	return fallback
}

func mediaPath(c *conn, msgId string, ext string) (string, error) {
	dir := c.path + "/media"
	if err := os.MkdirAll(dir, os.ModePerm); err != nil {
		return "", err
	}
	// the extension is remote-controlled too, so it gets the same treatment as
	// the id (the leading '.' is added here, not allowed through)
	cleanExt := ""
	if ext != "" {
		cleanExt = "." + safeName(strings.TrimPrefix(ext, "."), false)
	}
	return dir + "/" + safeName(msgId, false) + cleanExt, nil
}

// allowDot keeps '.', which the avatar cache needs because a chat id contains
// one and media paths must not, so the extension stays the only dot there.
func safeName(s string, allowDot bool) string {
	return strings.Map(func(r rune) rune {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-':
			return r
		case allowDot && r == '.':
			return r
		}
		return '_'
	}, s)
}

func copyToMedia(c *conn, msgId string, ext string, srcPath string) string {
	path, err := mediaPath(c, msgId, ext)
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("mkdir media error %v", err))
		return ""
	}
	src, err := os.Open(srcPath)
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("read media error %v", err))
		return ""
	}
	defer src.Close()
	dst, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0600)
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("write media error %v", err))
		return ""
	}
	_, err = io.Copy(dst, src)
	if cerr := dst.Close(); cerr != nil && err == nil {
		err = cerr
	}
	if err != nil {
		// a truncated copy is worse than none: the bubble would render a broken
		// file instead of re-downloading via the recorded fileId
		os.Remove(path)
		c.log(LogWarning, fmt.Sprintf("write media error %v", err))
		return ""
	}
	return path
}

type progressFile struct {
	f       *os.File
	c       *conn
	chatId  string
	msgId   string
	total   int64
	written int64
	lastPct int
}

func (p *progressFile) Write(b []byte) (int, error) {
	n, err := p.f.Write(b)
	p.written += int64(n)
	if p.total > 0 {
		pct := int(p.written * 100 / p.total)
		if pct > 99 {
			pct = 99 // completion is signalled by OnFileDownloaded
		}
		if pct > p.lastPct {
			p.lastPct = pct
			p.c.listener.OnDownloadProgress(p.chatId, p.msgId, pct)
		}
	}
	return n, err
}

// Seeking back to the start begins a fresh transfer (a retry) or the decrypt
// pass; reset the counter so a retry re-reports from 0 and decrypt writes (via
// WriteAt) don't resurrect stale progress.
func (p *progressFile) Seek(offset int64, whence int) (int64, error) {
	if offset == 0 && whence == io.SeekStart {
		p.written = 0
		p.lastPct = 0
	}
	return p.f.Seek(offset, whence)
}

func (p *progressFile) Read(b []byte) (int, error)               { return p.f.Read(b) }
func (p *progressFile) ReadAt(b []byte, off int64) (int, error)  { return p.f.ReadAt(b, off) }
func (p *progressFile) WriteAt(b []byte, off int64) (int, error) { return p.f.WriteAt(b, off) }
func (p *progressFile) Truncate(size int64) error                { return p.f.Truncate(size) }
func (p *progressFile) Stat() (os.FileInfo, error)               { return p.f.Stat() }

// PLACEHOLDER_MESSAGE_RESEND: the phone answers with the full message as a
// normal live event. The only way to refill a bodyless contact card too
// recent for on-demand history sync, which skips the stretch already synced
// to companions.
func RequestMessageResend(connId int, chatId, senderId, msgId string) bool {
	c := getConn(connId)
	if c == nil {
		return false
	}
	client := c.getClient()
	if client.Store.ID == nil {
		return false
	}
	chatJid, err := types.ParseJID(chatId)
	if err != nil {
		return false
	}
	senderJid, err := types.ParseJID(senderId)
	if err != nil {
		return false
	}
	msg := client.BuildUnavailableMessageRequest(chatJid, senderJid, msgId)
	_, err = client.SendMessage(context.Background(), client.Store.ID.ToNonAD(), msg,
		whatsmeow.SendRequestExtra{Peer: true})
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("resend request error %v", err))
		return false
	}
	c.log(LogDebug, fmt.Sprintf("resend requested: chat=%s msg=%s", chatId, msgId))
	return true
}

func RequestChatHistory(connId int, chatId string, oldestMsgId string, oldestTimeSent int64, oldestFromMe bool, count int, forExport bool) bool {
	c := getConn(connId)
	if c == nil {
		return false
	}
	client := c.getClient()
	if client.Store.ID == nil {
		return false
	}
	chatJid, err := types.ParseJID(chatId)
	if err != nil {
		return false
	}
	info := &types.MessageInfo{
		MessageSource: types.MessageSource{
			Chat:     chatJid,
			IsFromMe: oldestFromMe,
		},
		ID:        oldestMsgId,
		Timestamp: time.Unix(oldestTimeSent, 0),
	}
	msg := client.BuildHistorySyncRequest(info, count)
	if msg == nil {
		return false
	}
	// register before sending: a fast response must find the entry in place
	c.setPendingHistory(chatId, forExport)
	_, err = client.SendMessage(context.Background(), client.Store.ID.ToNonAD(), msg,
		whatsmeow.SendRequestExtra{Peer: true})
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("history request error %v", err))
		c.clearPendingActive()
		return false
	}
	c.log(LogDebug, fmt.Sprintf("history request sent: chat=%s anchor=%s t=%d count=%d export=%v",
		chatId, oldestMsgId, oldestTimeSent, count, forExport))
	return true
}

func GetPrivacySettings(connId int) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	s, err := c.getClient().TryFetchPrivacySettings(context.TODO(), true)
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("privacy fetch error %v", err))
		return ""
	}
	return fmt.Sprintf("last=%s\nonline=%s\nprofile=%s\nstatus=%s\nreadreceipts=%s",
		s.LastSeen, s.Online, s.Profile, s.Status, s.ReadReceipts)
}

func SetPrivacySetting(connId int, name string, value string) bool {
	c := getConn(connId)
	if c == nil {
		return false
	}
	_, err := c.getClient().SetPrivacySetting(context.TODO(),
		types.PrivacySettingType(name), types.PrivacySetting(value))
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("privacy set error %v", err))
		return false
	}
	return true
}

func GetMyAbout(connId int) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	client := c.getClient()
	if client.Store.ID == nil {
		return ""
	}
	self := client.Store.ID.ToNonAD()
	infos, err := client.GetUserInfo(context.TODO(), []types.JID{self})
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("about fetch error %v", err))
		return ""
	}
	return infos[self].Status
}

// "" covers both a contact with no About and one whose privacy settings hide
// it — the two are indistinguishable to a client, so the screen simply shows
// nothing rather than claiming either.
func GetUserAbout(connId int, userId string) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	jid, err := types.ParseJID(userId)
	if err != nil {
		return ""
	}
	infos, err := c.getClient().GetUserInfo(context.TODO(), []types.JID{jid})
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("peer about fetch error %v", err))
		return ""
	}
	return infos[jid].Status
}

// Cannot be mistaken for a chat id, which always carries an '@'. "Not
// registered" ("") must be told apart from "could not check": reporting the
// first for the second tells someone their contact is not on WhatsApp when the
// network merely dropped.
const ResolveNumberFailed = "failed"

func ResolveNumber(connId int, phone string) string {
	c := getConn(connId)
	if c == nil || phone == "" {
		return ResolveNumberFailed
	}
	found, err := c.getClient().IsOnWhatsApp(context.TODO(), []string{phone})
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("number lookup error %v", err))
		return ResolveNumberFailed
	}
	for _, r := range found {
		if !r.IsIn {
			continue
		}
		// JID is the "canonical" id, which for most accounts is now a @lid —
		// and a chat opened under that id showed nothing, because the message
		// is filed under the phone JID. Prefer the phone JID, which is what
		// this app keys chats by, and fall back only when there is none.
		if !r.PhoneNumber.IsEmpty() {
			return strFromJid(r.PhoneNumber)
		}
		return strFromJid(r.JID)
	}
	return ""
}

func GetMyName(connId int) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	return c.getClient().Store.PushName
}

func SetMyName(connId int, name string) bool {
	c := getConn(connId)
	if c == nil || name == "" {
		return false
	}
	client := c.getClient()
	if client.Store.ID == nil {
		return false
	}
	client.Store.PushName = name
	if err := client.Store.Save(context.TODO()); err != nil {
		c.log(LogWarning, fmt.Sprintf("save push name error %v", err))
		return false
	}
	if err := client.SendPresence(context.TODO(), types.PresenceAvailable); err != nil {
		c.log(LogWarning, fmt.Sprintf("announce push name error %v", err))
	}
	return true
}

func SetAbout(connId int, text string) bool {
	c := getConn(connId)
	if c == nil {
		return false
	}
	// upstream took a bare string until whatsmeow 662b0121; it now takes a
	// struct whose Text is a pointer, so an empty About ("" — the user clearing
	// it) still has to be sent as a non-nil pointer to an empty string rather
	// than as a nil field the server would read as "leave it alone"
	client := c.getClient()
	if err := client.SetStatusMessage(context.TODO(),
		types.SetStatusInput{Text: proto.String(text)}); err != nil {
		c.log(LogWarning, fmt.Sprintf("about set error %v", err))
		// The About is set with a GraphQL ("mex") mutation, and a bare
		// "400 Bad Request" from it does not say whether that channel works for
		// this client at all; a read-only mex query separates "this mutation is
		// refused" from "no mex query of ours is accepted".
		if _, probeErr := client.GetSubscribedNewsletters(context.TODO()); probeErr != nil {
			c.log(LogWarning, fmt.Sprintf("about set: mex probe query also failed %v", probeErr))
		} else {
			c.log(LogInfo, "about set: mex probe query succeeded")
		}
		return false
	}
	return true
}

func SetProfilePicture(connId int, jpegPath string) bool {
	c := getConn(connId)
	if c == nil {
		return false
	}
	client := c.getClient()
	if client.Store.ID == nil {
		return false
	}
	data, err := os.ReadFile(jpegPath)
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("read avatar error %v", err))
		return false
	}
	self := client.Store.ID.ToNonAD()
	// the own profile picture shares the group-photo IQ (namespace
	// w:profile:picture), targeting our own JID instead of a group.
	if _, err := client.SetGroupPhoto(context.TODO(), self, data); err != nil {
		c.log(LogWarning, fmt.Sprintf("set profile picture error %v", err))
		return false
	}
	selfId := strFromJid(self)
	os.Remove(avatarFilePath(c, selfId, true))
	os.Remove(avatarFilePath(c, selfId, false))
	return true
}

func markReceipt(c *conn, chatId string, senderId string, msgId string, ts time.Time, receiptType ...types.ReceiptType) {
	chatJid, err := types.ParseJID(chatId)
	if err != nil {
		return
	}
	senderJid, err := types.ParseJID(senderId)
	if err != nil {
		senderJid = chatJid
	}
	err = c.getClient().MarkRead(context.TODO(), []types.MessageID{msgId}, ts, chatJid, senderJid, receiptType...)
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("mark receipt error %v", err))
	}
}

func MarkRead(connId int, chatId string, senderId string, msgId string, timeSent int64) {
	c := getConn(connId)
	if c == nil {
		return
	}
	sent := time.Unix(timeSent, 0)
	c.setTimeRead(chatId, sent)
	markReceipt(c, chatId, senderId, msgId, sent)
}

func MarkVoicePlayed(connId int, chatId string, senderId string, msgId string) {
	c := getConn(connId)
	if c == nil {
		return
	}
	markReceipt(c, chatId, senderId, msgId, time.Now(), types.ReceiptTypePlayed)
}

func SubscribePresence(connId int, userId string) {
	c := getConn(connId)
	if c == nil {
		return
	}
	userJid, err := types.ParseJID(userId)
	if err != nil || userJid.Server == types.GroupServer {
		return
	}
	if err := c.getClient().SubscribePresence(context.TODO(), userJid); err != nil {
		c.log(LogWarning, fmt.Sprintf("subscribe presence error %v", err))
	}
}

func isMuteActive(act *waSyncAction.MuteAction) bool {
	if act == nil || !act.GetMuted() {
		return false
	}
	end := act.GetMuteEndTimestamp()
	return end <= 0 || time.UnixMilli(end).After(time.Now())
}

func chatMuted(c *conn, chatId string) bool {
	jid, err := types.ParseJID(chatId)
	if err != nil {
		return false
	}
	ctx := context.TODO()
	candidates := []types.JID{jid}
	if jid.Server == types.DefaultUserServer {
		if lid, _ := c.getClient().Store.LIDs.GetLIDForPN(ctx, jid); !lid.IsEmpty() {
			candidates = append(candidates, lid)
		}
	} else if jid.Server == types.HiddenUserServer {
		if pn, _ := c.getClient().Store.LIDs.GetPNForLID(ctx, jid); !pn.IsEmpty() {
			candidates = append(candidates, pn)
		}
	}
	for _, cand := range candidates {
		settings, err := c.getClient().Store.ChatSettings.GetChatSettings(ctx, cand)
		if err != nil || !settings.Found {
			continue
		}
		if !settings.MutedUntil.IsZero() && settings.MutedUntil.After(time.Now()) {
			return true
		}
	}
	return false
}

func MutedChats(connId int, chatIds string) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	var muted []string
	for _, id := range strings.Split(chatIds, "\n") {
		if id != "" && chatMuted(c, id) {
			muted = append(muted, id)
		}
	}
	return strings.Join(muted, "\n")
}

func SetMute(connId int, chatId string, muted bool) bool {
	c := getConn(connId)
	if c == nil {
		return false
	}
	jid, err := types.ParseJID(chatId)
	if err != nil {
		return false
	}
	ctx := context.TODO()
	// Mute a 1:1 chat under its LID (how modern WhatsApp indexes it) so the
	// change matches the phone and other devices; groups keep their @g.us JID.
	target := jid
	if jid.Server == types.DefaultUserServer {
		if lid, _ := c.getClient().Store.LIDs.GetLIDForPN(ctx, jid); !lid.IsEmpty() {
			target = lid
		}
	}
	// Reports the outcome: this used to be a void function that only logged, so
	// an offline toggle left the local flag flipped, the server none the wiser,
	// and nothing to tell the user or trigger a retry.
	if err := c.getClient().SendAppState(ctx, appstate.BuildMute(target, muted, 0)); err != nil {
		c.log(LogWarning, fmt.Sprintf("set mute error %v", err))
		return false
	}
	return true
}

func contactName(info types.ContactInfo) string {
	if info.FullName != "" {
		return info.FullName
	}
	if info.FirstName != "" {
		return info.FirstName
	}
	return info.PushName
}

func requestContacts(connId int) {
	c := getConn(connId)
	if c == nil {
		return
	}
	client := c.getClient()
	if client.Store.ID == nil {
		return
	}
	ctx := context.TODO()

	selfId := strFromJid(*client.Store.ID)
	selfName := client.Store.PushName
	c.listener.OnContact(selfId, selfName, phoneFromUserId(selfId), true, false, true)
	// our own LID alias, so anything addressed by it (a group message of ours)
	// resolves to our push name instead of an address-book label
	selfLid := ""
	if lid := client.Store.LID; !lid.IsEmpty() {
		selfLid = strFromJid(lid.ToNonAD())
		c.listener.OnContact(selfLid, selfName, "", true, false, false)
	}

	contacts, err := client.Store.Contacts.GetAllContacts(ctx)
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("get contacts error %v", err))
	} else {
		// entries keyed by both phone JID and LID JID, so group participants
		// (addressed by their LID) resolve to the address-book name too.
		// saved marks real address-book contacts; push-name-only entries are
		// kept for display but excluded from the contact search.
		type contactEntry struct {
			name  string
			saved bool
		}
		names := make(map[string]contactEntry)
		isSaved := func(info types.ContactInfo) bool {
			return info.FullName != "" || info.FirstName != ""
		}

		for jid, info := range contacts {
			if jid.Server == types.HiddenUserServer {
				continue
			}
			name := contactName(info)
			if name == "" {
				continue
			}
			names[strFromJid(jid)] = contactEntry{name, isSaved(info)}
			if lid, _ := client.Store.LIDs.GetLIDForPN(ctx, jid); !lid.IsEmpty() {
				// the LID alias keeps the name (group sender labels) but is
				// never marked saved: the phone-JID row represents the
				// contact in search, so the alias would only duplicate it
				names[strFromJid(lid)] = contactEntry{name, false}
			}
		}

		for jid, info := range contacts {
			if jid.Server != types.HiddenUserServer {
				continue
			}
			userId := strFromJid(jid)
			if _, known := names[userId]; known {
				continue
			}
			name := contactName(info)
			if pid, _ := client.Store.LIDs.GetPNForLID(ctx, jid); !pid.IsEmpty() {
				if pentry, ok := names[strFromJid(pid)]; ok {
					names[userId] = pentry
					continue
				}
			}
			if name != "" {
				names[userId] = contactEntry{name, isSaved(info)}
			}
		}

		// our own number can be in the address book too (people save
		// themselves); emitting it here as a normal contact overwrote the self
		// row above, losing the push name and the is_self flag — the chat with
		// yourself was then titled with the address-book label (often just the
		// number) and showed up in the contact search
		for userId, e := range names {
			if userId == selfId || userId == selfLid {
				continue
			}
			c.listener.OnContact(userId, e.name, phoneFromUserId(userId), false, false, e.saved)
		}
	}

	groups, err := client.GetJoinedGroups(ctx)
	if err != nil {
		c.log(LogWarning, fmt.Sprintf("get groups error %v", err))
	} else {
		for _, group := range groups {
			c.listener.OnContact(strFromJid(group.JID), group.Name, "", false, true, false)
		}
	}

	c.listener.OnContactsSynced()
}

func ResolveChatId(connId int, chatId string) string {
	c := getConn(connId)
	if c == nil {
		return chatId
	}
	jid, err := types.ParseJID(chatId)
	if err != nil {
		return chatId
	}
	return getChatId(c.getClient(), &jid, nil)
}

func GetAvatarPath(connId int, chatId string) string {
	return fetchAvatar(connId, chatId, true)
}

func GetAvatarFullPath(connId int, chatId string) string {
	return fetchAvatar(connId, chatId, false)
}

func avatarFilePath(c *conn, chatId string, preview bool) string {
	sanitized := safeName(chatId, true)
	suffix := ".jpg"
	if !preview {
		suffix = "_full.jpg"
	}
	return c.path + "/avatars/" + sanitized + suffix
}

func fetchAvatar(connId int, chatId string, preview bool) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	avatarPath := avatarFilePath(c, chatId, preview)
	cached := ""
	if info, err := os.Stat(avatarPath); err == nil {
		cached = avatarPath
		if time.Since(info.ModTime()) < time.Hour {
			return avatarPath
		}
	}

	// on any fetch failure, fall back to the (stale) cached file so avatars
	// don't vanish when offline or on a transient error
	chatJid, err := types.ParseJID(chatId)
	if err != nil {
		return cached
	}
	pic, err := c.getClient().GetProfilePictureInfo(context.TODO(), chatJid,
		&whatsmeow.GetProfilePictureParams{Preview: preview})
	if err != nil || pic == nil || len(pic.URL) == 0 {
		return cached
	}
	// A bounded client, NOT http.DefaultClient: a stalled CDN connection has no
	// deadline of its own and this runs on caller threads that matter (the
	// single notification worker, the 2-thread media pool), where one wedged
	// request blocks everything queued behind it for the process lifetime.
	// io.ReadAll is capped for the same reason.
	resp, err := avatarHTTP.Get(pic.URL)
	if err != nil {
		return cached
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return cached
	}
	// The extra byte tells "hit the cap" apart from "exactly the cap": the
	// LimitReader truncates silently, and the cut-off JPEG then replaced the
	// good cached copy that was the fallback.
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxAvatarBytes+1))
	if err != nil || len(data) > maxAvatarBytes {
		return cached
	}
	// Write to a temp file and rename: os.WriteFile truncates in place, and the
	// app decodes this very path concurrently — it could read a half-written
	// file, and the stale copy that would have been the fallback was already
	// destroyed. Rename is atomic within the directory.
	//
	// The temp name carries a nonce, like downloadToPath's ".part<nanos>". With
	// a fixed ".tmp" two concurrent fetches for the same id interleaved their
	// writes into one file and renamed a corrupt JPEG over the good cached copy
	// — reachable because AvatarLoader's pool dedups only within itself, while
	// ProfileActivity.loadAvatar and Bridge.openAvatar call in from other
	// executors.
	tmp := fmt.Sprintf("%s.tmp%d", avatarPath, time.Now().UnixNano())
	if err := os.WriteFile(tmp, data, 0600); err != nil {
		os.Remove(tmp)
		return cached
	}
	if err := os.Rename(tmp, avatarPath); err != nil {
		os.Remove(tmp)
		return cached
	}
	return avatarPath
}

var avatarHTTP = &http.Client{Timeout: 20 * time.Second}

const maxAvatarBytes = 8 << 20 // far beyond any profile picture

func GetCachedAvatarPath(connId int, chatId string) string {
	c := getConn(connId)
	if c == nil {
		return ""
	}
	path := avatarFilePath(c, chatId, true)
	if _, err := os.Stat(path); err != nil {
		return ""
	}
	return path
}

func handleEvent(connId int, c *conn, rawEvt interface{}) {
	switch evt := rawEvt.(type) {

	case *events.Connected:
		c.setState("connected")
		// announcing our own presence is required to receive others'
		go func() {
			if err := c.getClient().SendPresence(context.TODO(), types.PresenceAvailable); err != nil {
				c.log(LogWarning, fmt.Sprintf("send presence error %v", err))
			}
		}()

	case *events.Disconnected:
		c.setState("disconnected")

	case *events.LoggedOut:
		// whatsmeow deletes the local device store when the phone unlinks us,
		// which permanently poisons this client (Connect then fails with
		// ErrDeviceDeleted). Rebuild it the way Logout() does, or QR/pair login
		// could not succeed again until the process was restarted.
		c.setState("logged_out")
		if !c.resetDevice() {
			c.setState(StateStoreBroken)
		}

	case *events.ClientOutdated:
		c.setState("outdated")

	case *events.StreamReplaced:
		c.setState("disconnected")

	// whatsmeow marks these three disconnects as expected, so no
	// events.Disconnected follows them — without these cases the state stayed
	// "connecting" forever and the UI hung on it.
	case *events.TemporaryBan:
		c.log(LogWarning, "temporarily banned: "+evt.String())
		c.setState("disconnected")

	case *events.ConnectFailure:
		c.log(LogError, fmt.Sprintf("connect failure %s: %s", evt.Reason, evt.Message))
		c.setState("disconnected")

	case *events.CATRefreshError:
		c.log(LogError, fmt.Sprintf("CAT refresh error %v", evt.Error))
		c.setState("disconnected")

	case *events.AppStateSyncComplete:
		if evt.Name == appstate.WAPatchCriticalBlock {
			// Only if the socket really is up. App-state completion is dispatched
			// from its own goroutine after IQs that can take a minute, so it could
			// land after a Disconnected event and claim "connected" for a dead
			// socket — and because setState suppresses no-op transitions, the
			// later genuine Connected event then produced no callback at all.
			if cl := c.getClient(); cl != nil && cl.IsConnected() {
				c.setState("connected")
			}
		} else if evt.Name == appstate.WAPatchRegular {
			requestContactsAsync(connId)
		}

	case *events.OfflineSyncCompleted:
		requestContactsAsync(connId)

	case *events.Message:
		handleMessageFull(c, evt.Info, evt.Message, false, false, false, evt.IsViewOnce, false)

	case *events.UndecryptableMessage:
		handleUndecryptableMessage(c, evt)

	case *events.Receipt:
		handleReceipt(c, evt)

	case *events.ChatPresence:
		chatId := getChatId(c.getClient(), &evt.Chat, &evt.Sender)
		userId := getUserId(c.getClient(), &evt.Chat, &evt.Sender)
		state := "paused"
		if evt.State == types.ChatPresenceComposing {
			if evt.Media == types.ChatPresenceMediaAudio {
				state = "recording"
			} else {
				state = "typing"
			}
		}
		c.listener.OnChatState(chatId, userId, state)

	case *events.Mute:
		chatId := getChatId(c.getClient(), &evt.JID, nil)
		c.listener.OnMute(chatId, isMuteActive(evt.Action))

	case *events.Presence:
		userId := getUserId(c.getClient(), nil, &evt.From)
		lastSeen := int64(0)
		if !evt.LastSeen.IsZero() {
			lastSeen = evt.LastSeen.Unix()
		}
		c.listener.OnPresence(userId, !evt.Unavailable, lastSeen)

	case *events.HistorySync:
		handleHistorySync(c, evt)

	case *events.MediaRetry:
		handleMediaRetryEvent(c, evt)
	}
}

func handleMediaRetryEvent(c *conn, evt *events.MediaRetry) {
	pending, ok := c.takePendingMediaRetry(evt.MessageID)
	if !ok {
		return
	}
	// The timer is always set: it is armed before the entry is published, so
	// taking the entry out of the map under mx also gives us a fully
	// initialised struct. (Kept nil-safe because resetDevice walks the same
	// field.)
	if pending.timer != nil {
		pending.timer.Stop()
	}
	fail := func(why string, err error) {
		c.log(LogWarning, fmt.Sprintf("%s error %v", why, err))
		c.listener.OnFileDownloaded(pending.chatId, evt.MessageID, "", 3)
	}
	notif, err := whatsmeow.DecryptMediaRetryNotification(evt, pending.downloadable.GetMediaKey())
	if err != nil {
		fail("media retry decrypt", err)
		return
	}
	if notif.GetResult() != waMmsRetry.MediaRetryNotification_SUCCESS {
		fail("media retry result", fmt.Errorf("result=%v", notif.GetResult()))
		return
	}
	pending.setDirectPath(notif.GetDirectPath())
	// Off the event goroutine: this is a full media transfer (plus MAC, decrypt
	// and SHA256 passes), and whatsmeow dispatches events from its serialized
	// node-handler queue — running it inline stalled every queued message and
	// receipt for the duration of the download.
	msgId := evt.MessageID
	go func() {
		path, err := downloadToPath(c, pending.chatId, msgId, pending.downloadable, pending.ext, pending.total)
		if err != nil {
			fail("media retry download", err)
			return
		}
		c.listener.OnFileDownloaded(pending.chatId, msgId, path, 2)
	}()
}

func handleReceipt(c *conn, receipt *events.Receipt) {
	chatId := getChatId(c.getClient(), &receipt.Chat, &receipt.Sender)
	switch receipt.Type {
	case events.ReceiptTypeReadSelf:
		c.listener.OnChatReadSelf(chatId, "")
	case events.ReceiptTypeRead:
		for _, msgId := range receipt.MessageIDs {
			c.listener.OnMessageRead(chatId, msgId)
		}
	case events.ReceiptTypePlayed, types.ReceiptTypePlayedSelf:
		for _, msgId := range receipt.MessageIDs {
			c.listener.OnMessagePlayed(chatId, msgId)
		}
	}
}

// Counted off the raw key, so an entry this app cannot parse or display still
// counts — a page of nothing but stubs looked empty, and the walk read that as
// the end of the history and left everything older unfetched forever.
func historyPage(msgs []*waHistorySync.HistorySyncMsg) (size int, oldestId string, oldestTime int64, oldestFromMe bool) {
	for _, syncMessage := range msgs {
		webMessageInfo := syncMessage.Message
		id := webMessageInfo.GetKey().GetID()
		if id == "" {
			continue // cannot anchor the next page: the app reads "" as exhausted
		}
		size++
		// <=, not <: whatsapp timestamps are second-resolution, so a page
		// routinely ends on several entries sharing the oldest second, and
		// anchoring on the first of them makes the next page re-deliver that
		// same second instead of moving past it
		t := int64(webMessageInfo.GetMessageTimestamp())
		if oldestId == "" || t <= oldestTime {
			oldestId, oldestTime, oldestFromMe = id, t, webMessageInfo.GetKey().GetFromMe()
		}
	}
	return
}

func handleHistorySync(c *conn, historySync *events.HistorySync) {
	client := c.getClient()
	if client.Store.ID == nil {
		return
	}
	selfJid := *client.Store.ID

	onDemand := historySync.Data.GetSyncType() == waHistorySync.HistorySync_ON_DEMAND

	progress := int(historySync.Data.GetProgress())
	c.listener.OnSyncProgress(progress)

	c.log(LogDebug, fmt.Sprintf("history sync: type=%s conversations=%d progress=%d",
		historySync.Data.GetSyncType(), len(historySync.Data.GetConversations()), progress))

	answeredPending := false
	for _, conversation := range historySync.Data.GetConversations() {
		chatJid, err := types.ParseJID(conversation.GetID())
		if err != nil {
			continue
		}
		if isStatusBroadcast(chatJid) {
			continue
		}

		type parsedMsg struct {
			info      *types.MessageInfo
			msg       *waE2E.Message
			peerRead  bool
			played    bool
			reactions []*waWeb.Reaction
		}
		pageSize, oldestId, oldestTime, oldestFromMe := historyPage(conversation.GetMessages())

		var parsed []parsedMsg
		for _, syncMessage := range conversation.GetMessages() {
			webMessageInfo := syncMessage.Message
			messageInfo := parseWebMessageInfo(selfJid, chatJid, webMessageInfo)
			message := webMessageInfo.GetMessage()
			if messageInfo == nil || message == nil {
				continue
			}
			status := webMessageInfo.GetStatus()
			peerRead := status >= waWeb.WebMessageInfo_READ
			played := status >= waWeb.WebMessageInfo_PLAYED
			parsed = append(parsed, parsedMsg{messageInfo, message, peerRead, played,
				webMessageInfo.GetReactions()})
		}

		sort.SliceStable(parsed, func(i, j int) bool {
			return parsed[i].info.Timestamp.After(parsed[j].info.Timestamp)
		})

		convChatId := getChatId(client, &chatJid, nil)
		forExport := onDemand && c.exportRouted(convChatId)
		// answers the request AFTER the page's messages are delivered below;
		// the true oldest of the page (even a non-displayable entry) anchors
		// the app's next page request. Fired for every on-demand conversation;
		// the app ignores it unless it matches the request in flight.
		answerRequest := func() {
			if !onDemand {
				return
			}
			answeredPending = true
			c.clearPendingActive()
			c.listener.OnChatHistoryDelivered(convChatId, pageSize, forExport,
				oldestId, oldestTime, oldestFromMe)
		}
		if forExport {
			for _, p := range parsed {
				content, ok := getMessageContent(p.msg, false)
				if !ok {
					continue
				}
				c.listener.OnExportMessage(convChatId, p.info.ID,
					getUserId(client, &p.info.Chat, &p.info.Sender), content.text,
					p.info.IsFromMe, p.info.Timestamp.Unix(), content.msgType,
					content.fileId, p.info.PushName, len(p.info.Edit) > 0)
			}
			answerRequest()
			continue
		}

		unreadLeft := int(conversation.GetUnreadCount())
		if onDemand {
			unreadLeft = 0
		}

		lastMessageTime := int64(0)
		hasMessages := false
		for _, p := range parsed {
			isRead := true
			// only messages that become chat rows may consume the unread
			// budget; reactions/protocol entries would otherwise eat it and
			// leave real unread messages marked read
			if !p.info.IsFromMe && unreadLeft > 0 && isDisplayable(p.msg) {
				isRead = false
				unreadLeft--
			}
			handleMessageFull(c, *p.info, p.msg, isRead, p.peerRead, true, false, false)
			if p.played {
				c.listener.OnMessagePlayed(getChatId(client, &p.info.Chat, &p.info.Sender), p.info.ID)
			}
			for _, reaction := range p.reactions {
				sender := reactionSender(c, chatJid, reaction.GetKey())
				if sender == "" || reaction.GetText() == "" {
					continue
				}
				c.listener.OnReaction(
					getChatId(client, &p.info.Chat, &p.info.Sender),
					p.info.ID, sender, reaction.GetText())
			}
			hasMessages = true
			if t := p.info.Timestamp.Unix(); t > lastMessageTime {
				lastMessageTime = t
			}
		}

		if hasMessages && !onDemand {
			chatId := getChatId(client, &chatJid, nil)
			c.listener.OnChat(chatId, conversation.GetName(), int(conversation.GetUnreadCount()),
				conversation.GetArchived(), lastMessageTime)
		}
		answerRequest()
	}

	// an empty on-demand sync means the start of the chat's history was
	// reached; it names no conversation, so attribute it to the single
	// outstanding request
	if onDemand && !answeredPending {
		if chatId, forExport, ok := c.takePendingHistory(); ok {
			c.log(LogDebug, fmt.Sprintf("on-demand page empty: chat=%s export=%v", chatId, forExport))
			c.listener.OnChatHistoryDelivered(chatId, 0, forExport, "", 0, false)
		}
	}
}

func isDisplayable(msg *waE2E.Message) bool {
	if msg == nil || msg.GetReactionMessage() != nil || msg.GetProtocolMessage() != nil {
		return false
	}
	_, ok := getMessageContent(msg, false)
	return ok
}

// Live group events carry LIDs, while history keys and the local send echo
// carry phone JIDs. Without normalizing to the phone JID, the same user's
// reaction can be stored twice and removals can miss the stored row.
func reactionUserId(c *conn, jid types.JID) string {
	if jid.Server == types.HiddenUserServer {
		if pn, _ := c.getClient().Store.LIDs.GetPNForLID(context.TODO(), jid); !pn.IsEmpty() {
			return strFromJid(pn)
		}
	}
	return strFromJid(jid)
}

func reactionSender(c *conn, chatJid types.JID, key *waCommon.MessageKey) string {
	if key == nil {
		return ""
	}
	if key.GetFromMe() {
		if c.getClient().Store.ID != nil {
			return strFromJid(*c.getClient().Store.ID)
		}
		return ""
	}
	if part := key.GetParticipant(); part != "" {
		if jid, err := types.ParseJID(part); err == nil {
			return reactionUserId(c, jid)
		}
	}
	return getChatId(c.getClient(), &chatJid, nil)
}

func (c *conn) isSelfChat(chatId string) bool {
	return c.getClient().Store.ID != nil && chatId == strFromJid(*c.getClient().Store.ID)
}

func (c *conn) messageIsRead(chatId string, fromMe bool, timeSent time.Time, isSyncRead bool, peerRead bool) bool {
	isSelf := c.isSelfChat(chatId)
	if fromMe {
		return peerRead || isSelf
	}
	// Strictly BEFORE the read watermark. The watermark is the second of the
	// last message we marked read, and whatsapp timestamps are second-resolution:
	// with `!After` a brand-new message arriving in that same second counted as
	// already read, so it produced no unread badge and no notification at all.
	return isSyncRead || isSelf || timeSent.Before(c.getTimeRead(chatId))
}

func handleMessageFull(c *conn, messageInfo types.MessageInfo, msg *waE2E.Message, isSyncRead bool, peerRead bool, isHistory bool, isViewOnce bool, ownSend bool) {
	if isStatusBroadcast(messageInfo.Chat) {
		return
	}
	if r := msg.GetReactionMessage(); r != nil {
		if key := r.GetKey(); key != nil && key.GetID() != "" {
			chatId := getChatId(c.getClient(), &messageInfo.Chat, &messageInfo.Sender)
			senderId := reactionUserId(c, messageInfo.Sender)
			c.listener.OnReaction(chatId, key.GetID(), senderId, r.GetText())
		}
		return
	}

	isEdited := len(messageInfo.Edit) > 0
	// a live edit updates an existing row's text in place; it must not change
	// the row's stored timestamp (which would reorder the chat), so its
	// delivery carries timeSent 0 and the app keeps the original order
	editInPlace := false
	if pm := msg.GetProtocolMessage(); pm != nil {
		switch pm.GetType() {
		case waE2E.ProtocolMessage_MESSAGE_EDIT:
			edited := pm.GetEditedMessage()
			if edited == nil {
				return
			}
			messageInfo.ID = pm.GetKey().GetID()
			msg = edited
			isEdited = true
			editInPlace = true
		case waE2E.ProtocolMessage_REVOKE:
			chatId := getChatId(c.getClient(), &messageInfo.Chat, &messageInfo.Sender)
			c.listener.OnMessageDeleted(chatId, pm.GetKey().GetID())
			return
		default:
			return
		}
	}

	content, ok := getMessageContent(msg, ownSend)
	if !ok {
		return
	}
	// evt.IsViewOnce (whatsmeow's UnwrapRaw) is the only reliable signal for a
	// view-once voice message: unlike image/video, the inner AudioMessage
	// unwrapped from a ViewOnceMessageV2Extension carries no ViewOnce bit of
	// its own, so getMessageContent's per-type check can't catch it — without
	// this override it would be treated as ordinary downloadable audio whose
	// media key was never shared to this (linked) device, and the download
	// would simply fail silently.
	if isViewOnce {
		// keep the context this message carries: replacing the whole struct
		// dropped the quote it was replying to (and its forwarded flag), leaving
		// an orphan placeholder bubble with no link back to the original
		content = msgContent{
			msgType:    viewOnceType,
			quotedId:   content.quotedId,
			quotedText: content.quotedText,
			quotedType: content.quotedType,
			forwarded:  content.forwarded,
		}
	}

	chatId := getChatId(c.getClient(), &messageInfo.Chat, &messageInfo.Sender)
	senderId := getUserId(c.getClient(), &messageInfo.Chat, &messageInfo.Sender)
	fromMe := messageInfo.IsFromMe
	timeSent := messageInfo.Timestamp
	isRead := c.messageIsRead(chatId, fromMe, timeSent, isSyncRead, peerRead)

	emitTime := timeSent.Unix()
	if editInPlace {
		emitTime = 0
	}
	c.listener.OnMessage(chatId, messageInfo.ID, senderId, content.text, fromMe, emitTime,
		isRead, content.msgType, content.fileId, content.latitude, content.longitude,
		isHistory, isEdited, content.quotedId, content.quotedText,
		content.quotedType, messageInfo.PushName, content.forwarded)
}

// The one UndecryptableMessage case that never resolves into a normal
// *events.Message: a view-once message on a linked/companion device.
// WhatsApp intentionally never forwards view-once media keys to companion
// devices, so the phone reports it as permanently unavailable (type
// "view_once") instead of encrypted content, and no retry
// will ever turn it into a decryptable one. Every other UndecryptableMessage
// cause is followed by an automatic retry that arrives as a normal Message
// event, so this must not emit anything for those.
func handleUndecryptableMessage(c *conn, evt *events.UndecryptableMessage) {
	if evt.UnavailableType != events.UnavailableTypeViewOnce {
		return
	}
	if isStatusBroadcast(evt.Info.Chat) {
		return
	}
	chatId := getChatId(c.getClient(), &evt.Info.Chat, &evt.Info.Sender)
	senderId := getUserId(c.getClient(), &evt.Info.Chat, &evt.Info.Sender)
	timeSent := evt.Info.Timestamp
	isRead := c.messageIsRead(chatId, evt.Info.IsFromMe, timeSent, false, false)
	c.listener.OnMessage(chatId, evt.Info.ID, senderId, "", evt.Info.IsFromMe, timeSent.Unix(),
		isRead, viewOnceType, "", 0, 0, false, false, "", "", "", evt.Info.PushName, false)
}

type msgContent struct {
	text       string
	msgType    string
	fileId     string
	quotedId   string
	quotedText string
	quotedType string
	forwarded  bool
	latitude  float64
	longitude float64
}

func extractQuote(ci *waE2E.ContextInfo) (id string, text string, msgType string) {
	if ci == nil {
		return "", "", ""
	}
	id = ci.GetStanzaID()
	if id == "" {
		return "", "", ""
	}
	quoted := ci.GetQuotedMessage()
	if quoted == nil {
		return id, "", ""
	}
	qc, ok := getMessageContent(quoted, false)
	if !ok {
		return id, "", ""
	}
	if qc.msgType == "audio" {
		return id, "", qc.msgType
	}
	return id, qc.text, qc.msgType
}

// The first line is ALWAYS the header the app treats as the name (a nameless
// card uses its number), so every following line is a phone by construction.
// waid is the card's WhatsApp id when the vcard names one — the app's
// "Message" button opens a chat with it directly, no server lookup.
func contactText(name, vcard string) (text, waid string) {
	phones := []string{}
	seen := map[string]bool{}
	for _, l := range strings.Split(vcard, "\n") {
		l = strings.TrimRight(l, "\r")
		u := strings.ToUpper(l)
		if !strings.HasPrefix(u, "TEL") && !strings.Contains(u, ".TEL") {
			continue
		}
		if waid == "" {
			if i := strings.Index(l, "waid="); i >= 0 {
				w := l[i+5:]
				if j := strings.IndexAny(w, ";:"); j >= 0 {
					w = w[:j]
				}
				waid = w
			}
		}
		i := strings.LastIndex(l, ":")
		if i < 0 {
			continue
		}
		num := strings.TrimSpace(l[i+1:])
		if num == "" || seen[num] {
			continue
		}
		seen[num] = true
		phones = append(phones, num)
	}
	if name == "" {
		if len(phones) == 0 {
			return "", waid
		}
		name = phones[0]
	}
	return strings.Join(append([]string{name}, phones...), "\n"), waid
}

func fromContext(ci *waE2E.ContextInfo) msgContent {
	id, text, msgType := extractQuote(ci)
	return msgContent{
		quotedId:   id,
		quotedText: text,
		quotedType: msgType,
		forwarded:  ci.GetIsForwarded(),
	}
}

// View-once keys are never shared with linked/companion devices, so it can only
// be opened on the primary phone and there is nothing here to store or
// download. Carried as a message TYPE rather than a canned English body so
// previewLabel owns (and translates) the label.
const viewOnceType = "viewonce"

// ownSend: the ViewOnce bit governs what the RECIPIENT may do with the media,
// so on the echo of our own send it must be ignored — the file is on this disk
// already, and honouring it turned our own outgoing photo into the locked
// placeholder an incoming view-once gets.
func getMessageContent(msg *waE2E.Message, ownSend bool) (msgContent, bool) {
	if msg == nil {
		return msgContent{}, false
	}
	if text := msg.GetConversation(); len(text) > 0 {
		return msgContent{text: text}, true
	}
	if ext := msg.GetExtendedTextMessage(); ext != nil {
		m := fromContext(ext.GetContextInfo())
		m.text = ext.GetText()
		return m, true
	}
	if img := msg.GetImageMessage(); img != nil {
		if img.GetViewOnce() && !ownSend {
			return msgContent{msgType: viewOnceType}, true
		}
		m := fromContext(img.GetContextInfo())
		m.text, m.msgType, m.fileId = img.GetCaption(), "image", encodeFileId("img", img)
		return m, true
	}
	if vid := msg.GetVideoMessage(); vid != nil {
		if vid.GetViewOnce() && !ownSend {
			return msgContent{msgType: viewOnceType}, true
		}
		m := fromContext(vid.GetContextInfo())
		m.text, m.msgType, m.fileId = vid.GetCaption(), "video", encodeFileId("vid", vid)
		return m, true
	}
	// PTV ("video message" / round video note) is a VideoMessage in a separate
	// field; treat it like a normal video so it reuses the "vid" download path.
	if ptv := msg.GetPtvMessage(); ptv != nil {
		if ptv.GetViewOnce() && !ownSend {
			return msgContent{msgType: viewOnceType}, true
		}
		m := fromContext(ptv.GetContextInfo())
		m.text, m.msgType, m.fileId = ptv.GetCaption(), "video", encodeFileId("vid", ptv)
		return m, true
	}
	if aud := msg.GetAudioMessage(); aud != nil {
		if aud.GetViewOnce() && !ownSend {
			return msgContent{msgType: viewOnceType}, true
		}
		m := fromContext(aud.GetContextInfo())
		m.text = formatDuration(int(aud.GetSeconds()))
		m.msgType, m.fileId = "audio", encodeFileId("aud", aud)
		return m, true
	}
	if doc := msg.GetDocumentMessage(); doc != nil {
		m := fromContext(doc.GetContextInfo())
		m.text, m.msgType, m.fileId = doc.GetFileName(), "document", encodeFileId("doc", doc)
		return m, true
	}
	if stk := msg.GetStickerMessage(); stk != nil {
		m := fromContext(stk.GetContextInfo())
		m.msgType, m.fileId = "sticker", encodeFileId("stk", stk)
		return m, true
	}
	if con := msg.GetContactMessage(); con != nil {
		m := fromContext(con.GetContextInfo())
		m.msgType = "contact"
		m.text, m.fileId = contactText(con.GetDisplayName(), con.GetVcard())
		return m, true
	}
	if arr := msg.GetContactsArrayMessage(); arr != nil {
		m := fromContext(arr.GetContextInfo())
		m.msgType = "contact"
		// several people on one card: no single id for "Message" to open.
		// A blank line separates people so the app never mistakes the next
		// person's name for another phone number of the first.
		var parts []string
		for _, con := range arr.GetContacts() {
			if t, _ := contactText(con.GetDisplayName(), con.GetVcard()); t != "" {
				parts = append(parts, t)
			}
		}
		m.text = strings.Join(parts, "\n\n")
		return m, true
	}
	if loc := msg.GetLocationMessage(); loc != nil {
		m := fromContext(loc.GetContextInfo())
		m.text, m.msgType = loc.GetName(), "location"
		m.latitude, m.longitude = loc.GetDegreesLatitude(), loc.GetDegreesLongitude()
		return m, true
	}
	if msg.GetPollCreationMessage() != nil || msg.GetPollCreationMessageV2() != nil ||
		msg.GetPollCreationMessageV3() != nil {
		return msgContent{msgType: "poll"}, true
	}
	// A labelled TYPE rather than ok=false for the message kinds this client
	// can't render: dropping them left an invisible hole in the conversation
	// (the chat didn't even move up the list, and a reply quoting one showed a
	// blank quote), which reads as lost messages. These used to carry English
	// prose ("[Poll]") as their body, which then appeared verbatim
	// mid-conversation whatever the language, and which the exporter and
	// previewLabel both had to special-case around; previewLabel (Db.kt) owns
	// the type -> wording mapping.
	if live := msg.GetLiveLocationMessage(); live != nil {
		m := fromContext(live.GetContextInfo())
		m.msgType = "livelocation"
		return m, true
	}
	if msg.GetPollUpdateMessage() != nil {
		return msgContent{msgType: "pollvote"}, true
	}
	if msg.GetEventMessage() != nil {
		return msgContent{msgType: "event"}, true
	}
	if msg.GetGroupInviteMessage() != nil {
		return msgContent{msgType: "groupinvite"}, true
	}
	if msg.GetViewOnceMessage() != nil || msg.GetViewOnceMessageV2() != nil ||
		msg.GetViewOnceMessageV2Extension() != nil {
		if !ownSend {
			return msgContent{msgType: viewOnceType}, true
		}
		for _, w := range []*waE2E.FutureProofMessage{
			msg.GetViewOnceMessage(), msg.GetViewOnceMessageV2(),
			msg.GetViewOnceMessageV2Extension(),
		} {
			if w.GetMessage() != nil {
				return getMessageContent(w.GetMessage(), true)
			}
		}
		// an empty wrapper: still a message, and falling out of here reached the
		// "nothing displayable" return, which drops the row entirely
		return msgContent{msgType: viewOnceType}, true
	}
	if eph := msg.GetEphemeralMessage(); eph != nil {
		return getMessageContent(eph.GetMessage(), ownSend)
	}
	if edited := msg.GetEditedMessage(); edited != nil {
		return getMessageContent(edited.GetMessage(), ownSend)
	}
	return msgContent{}, false
}

func strFromJid(jid types.JID) string {
	return jid.User + "@" + jid.Server
}

func phoneFromUserId(userId string) string {
	phone := ""
	if strings.HasSuffix(userId, "@"+types.DefaultUserServer) {
		phone = strings.TrimSuffix(userId, "@"+types.DefaultUserServer)
	}
	return phone
}

func isStatusBroadcast(jid types.JID) bool {
	return jid.Server == types.BroadcastServer && jid.User == "status"
}

func getChatId(client *whatsmeow.Client, chatJid *types.JID, senderJid *types.JID) string {
	if chatJid == nil {
		return ""
	} else if chatJid.Server == types.BroadcastServer && chatJid.User == "status" {
		return strFromJid(*chatJid)
	} else if chatJid.Server == types.BroadcastServer && chatJid.User != "status" {
		if senderJid != nil {
			userId := getUserId(client, nil, senderJid)
			if client.Store.ID != nil && userId == strFromJid(*client.Store.ID) {
				return strFromJid(*chatJid)
			}
			return userId
		}
	} else if chatJid.Server == types.HiddenUserServer {
		ctx := context.TODO()
		if pChatJid, _ := client.Store.LIDs.GetPNForLID(ctx, *chatJid); !pChatJid.IsEmpty() {
			return strFromJid(pChatJid)
		}
	}
	return strFromJid(*chatJid)
}

func getUserId(client *whatsmeow.Client, chatJid *types.JID, userJid *types.JID) string {
	if userJid == nil {
		return ""
	} else if chatJid != nil && chatJid.Server == types.GroupServer {
		return strFromJid(*userJid)
	} else if userJid.Server == types.HiddenUserServer {
		ctx := context.TODO()
		if pUserJid, _ := client.Store.LIDs.GetPNForLID(ctx, *userJid); !pUserJid.IsEmpty() {
			return strFromJid(pUserJid)
		}
	}
	return strFromJid(*userJid)
}

func parseWebMessageInfo(selfJid types.JID, chatJid types.JID, webMsg *waWeb.WebMessageInfo) *types.MessageInfo {
	info := types.MessageInfo{
		MessageSource: types.MessageSource{
			Chat:     chatJid,
			IsFromMe: webMsg.GetKey().GetFromMe(),
			IsGroup:  chatJid.Server == types.GroupServer,
		},
		ID:        webMsg.GetKey().GetID(),
		PushName:  webMsg.GetPushName(),
		Timestamp: time.Unix(int64(webMsg.GetMessageTimestamp()), 0),
	}
	if info.IsFromMe {
		info.Sender = selfJid
	} else if webMsg.GetParticipant() != "" {
		info.Sender, _ = types.ParseJID(webMsg.GetParticipant())
	} else if webMsg.GetKey().GetParticipant() != "" {
		info.Sender, _ = types.ParseJID(webMsg.GetKey().GetParticipant())
	} else {
		info.Sender = chatJid
	}
	if info.Sender.IsEmpty() {
		return nil
	}
	return &info
}
