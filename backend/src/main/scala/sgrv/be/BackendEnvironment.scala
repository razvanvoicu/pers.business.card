package sgrv.be

import sgrv.be.auth.CardConfig
import sgrv.be.contact.MessageStore
import sgrv.be.core.Capability

/** Services available to dynamically discovered backend routes. */
type BackendEnvironment = MessageStore & CardConfig

private[be] object BackendCapabilities:
  val messageStore: Capability[MessageStore] = Capability("message-store")
  val cardConfig: Capability[CardConfig] = Capability("card-config")
