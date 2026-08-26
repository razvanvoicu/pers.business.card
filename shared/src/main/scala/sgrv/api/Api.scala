package sgrv.api

import zio.json.{DeriveJsonCodec, JsonCodec, jsonNoExtraFields}

@jsonNoExtraFields
final case class SubmitMessageRequest(message: String)

object SubmitMessageRequest:
  given JsonCodec[SubmitMessageRequest] = DeriveJsonCodec.gen[SubmitMessageRequest]

@jsonNoExtraFields
final case class PersonalParticulars(
    name: String,
    designation: String,
    tagline: String,
    link1: String,
    link2: String,
    link3: String,
    link4: String
)

object PersonalParticulars:
  given JsonCodec[PersonalParticulars] = DeriveJsonCodec.gen[PersonalParticulars]
