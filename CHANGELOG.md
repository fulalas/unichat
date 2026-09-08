# UniChat 0.59.1

### New

* **Links you send to WhatsApp and Signal contacts now carry a preview card** — title, description and image travel with the message, so the other side sees the same card you see.

# UniChat 0.57.3

### New

* **Pick several chats at once on the main screen** — long-press to start, drag to pick a range, then mute, unmute or delete them together.
* **The forward and share list now shows avatars**, each with the ring that says which app the contact belongs to.

### Fixed

* The screen no longer lights up between two voice messages played at the ear, and stays off until you move the phone away.
* A voice message held at the ear now plays at the speed you chose, and keeps the call volume there.
* Deleting a message returns the chat to its old place in the list; a chat with no messages left stays where it is.

### Improvements

* The chat list and the message list share the same selection code.
* Removed duplicated code (QR renderer, avatar loader, presence line, message sort order and more) and deleted dead code.
* Each protocol declares what it can do, instead of the app guessing from the id.
* Chat-list search is debounced, sender names are remembered per message, and a missing index was added for outgoing read receipts.
* Code comments that don't record a past bug are gone.

# UniChat 0.55.7 — 2026-09-03

### New

* Telegram reactions that arrived while the app was closed now show up.
* Deleting a chat now deletes it on the server too (WhatsApp, Telegram, Signal); a chat deleted on another device disappears on UniChat as well.

### Fixed

* Telegram supergroups/channels can't be deleted.
* Can't edit messages with video or photos.
* A voice note that failed to send can't be played.
* Contact search listed some Signal people twice.
* Forwarding used tap order, not chat order.
* A retried message lost its original time, and the chat list showed the wrong one.
* Only your own messages pulled the chat to the bottom; now any new one does.
* Opening a chat didn't always re-read the newest messages.

### Improvements

* Unchanged reactions are no longer rewritten.
* Chat-open refresh moved off the history thread, so scrolling up isn't blocked.

# UniChat 0.54.9

Changes since 0.50.9.

### New

* **Telegram reactions that arrived while the app was closed now show up.** UniChat tells Telegram which messages are on screen and fetches unread reactions when a chat reports some.
* **Deleting a chat now deletes it on the server too**, on WhatsApp, Telegram and Signal — not just on this phone. A chat you delete on another device disappears here as well.
* **About now has a donation link** for the UniChat project.

### Fixed

* Telegram supergroups and channels could not be deleted; UniChat now leaves them instead.
* You are warned when a chat was removed here but the other devices could not be told.
* A voice note that failed to send tried to send again instead of playing.
* Contact search listed some people twice on Signal.
* Forwarding several messages sent them in the order you tapped them, not the order they appear in the chat.
* A message that failed and was retried later lost its original time, and the chat list showed the wrong time with it.
* An incoming message only pulled the chat to the bottom when it was yours; now any new message does, while a chat you scrolled up in stays put.
* Opening a chat did not always re-read the newest messages.

### Improvements

* Reactions that did not change are no longer rewritten.
* The refresh when opening a chat runs off the history thread, so scrolling up is no longer blocked.

# UniChat 0.50.9 — 2026-08-31

### New

* **The app speaks 11 more languages**: Portuguese (Brazil), Spanish, Russian, German, Italian, French, Ukrainian, Turkish, Polish, Dutch and Chinese (Simplified). Dates, clock times and counts follow the language you pick.
* **Signal read receipts**: a message read by the other side now shows the double tick, and a voice note they played is marked as played.
* **Messages show up the moment you send them**. If the deliver fails, a red exclamation mark in the corner of the message will appear, and the app will keep retrying on its own ten times. Tap the exclamation mark to manually retry.
* **Reading a Signal chat on another device now marks it as read in UniChat too**.
* **Chats open at the first unread message**, instead of at the very bottom.

### Fixed

* Chats reopen where you left off even if the app is killed.
* Reconnecting WhatsApp unmuted every Signal chat.
* A Signal account could be left unusable when the main device unlinked it.
* Telegram mutes were never cleared, and moves to the archive were missed.
* Telegram was writing message text and contact details into the phone's system log.
* Telegram voice notes kept the unplayed dot after being listened to elsewhere.
* Deleted chats came back.
* Read receipts landed on the wrong message.
* New messages vanished while the list was still updating.
* An arriving message scrolled the chat away from what you were reading.
* Closing the search moved the chat somewhere else.
* A search hit on top of a link was unreadable.
* Tapping a quote did not jump to the quoted message properly.
* The mic kept recording the voice message when a call took over.
* Old notification lines showed up after relinking an account.
* Images and avatars stayed blank after a failed decode.
* Logging out of WhatsApp wiped Telegram and Signal state with it.
* The login screen now says when a QR code can't work.
* Shared files are sent with the right file type.
* Several crashes from work running on the wrong thread.

### Improvements

* Opening a chat with a lot of unread messages is no longer slow.
* Signal read receipts are sent in batches instead of one call per message.
* The app ships only the languages it supports, instead of the ~70 that came bundled with Android's UI library.

# UniChat 0.46.16 — 2026-08-28

First UniChat release

Free lightweight native Android client for Telegram, Signal and WhatsApp. Link one account or all three: their chats share one list, one local database and the same screens, with a different accent color for each.

* Text, photos, videos, voice messages, documents, stickers, locations and contacts
* Reply, react, copy, edit, delete, save to Downloads, etc
* Search chats and messages, link previews, photo viewer with zoom
* Notifications per chat, presence and typing, per-account privacy settings
* Light and dark themes (true black for OLED), adjustable font size
* No ads, no telemetry
