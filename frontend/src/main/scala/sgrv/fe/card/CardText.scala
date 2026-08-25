package sgrv.fe.card

/** Every piece of user-facing text on the business card that isn't personal data, gathered in one place so it can
  * be edited without touching layout or rendering code. The name, designation, tagline, and link URLs are
  * personal data and aren't kept here: they're fetched from `GET /personalparticulars` at runtime instead (see
  * `BusinessCard.fetchPersonalParticulars`), sourced on the backend from `CardConfig`.
  */
private[fe] object CardText:
  val ResumeLinkLabel = "Resume"

  val LinkedInLabel = "LinkedIn"

  val Link13LeftLabel = "Blog"
  val Link13RightLabel = "Portfolio"

  val ContactButtonLabel = "Contact me"

  val MessageTitle = "Drop me a line"
  val MessagePlaceholder = "Type your message here…"
  val MessageSubmitLabel = "Submit"
  val MessageCancelLabel = "Cancel"

  val MessageWaitingLabel = "Sending your message…"
  val MessageSuccessText = "Message successfully sent"
  val MessageOkLabel = "OK"

  val TooExtremeMessage = "Please make this window taller to view this card."
