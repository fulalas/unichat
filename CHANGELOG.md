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
