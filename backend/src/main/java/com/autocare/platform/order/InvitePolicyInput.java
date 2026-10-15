package com.autocare.platform.order;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
public final class InvitePolicyInput {
 static JsonNode draft(String raw,boolean update){var b=CouponInput.parse(raw);if(update)CouponInput.exact(b,"scope","source_note","valid_from","valid_to","inviter_coupon_id","inviter_publication_revision","invitee_coupon_id","invitee_publication_revision","expected_revision");else CouponInput.exact(b,"scope","source_note","valid_from","valid_to","inviter_coupon_id","inviter_publication_revision","invitee_coupon_id","invitee_publication_revision");if(update)CouponInput.version(b.get("expected_revision"));
  if(!b.get("scope").isTextual()||!Set.of("OWNER","STAFF").contains(b.get("scope").textValue()))throw InvitationInput.bad();var note=b.get("source_note");if(!note.isTextual()||!note.textValue().equals(note.textValue().strip())||note.textValue().codePointCount(0,note.textValue().length())<2||note.textValue().codePointCount(0,note.textValue().length())>256||note.textValue().chars().anyMatch(Character::isISOControl))throw InvitationInput.bad();if(!CouponInput.time(b.get("valid_from")).isBefore(CouponInput.time(b.get("valid_to"))))throw InvitationInput.bad();CouponInput.id(b.get("invitee_coupon_id"));CouponInput.id(b.get("invitee_publication_revision"));
  if("OWNER".equals(b.get("scope").textValue())){CouponInput.id(b.get("inviter_coupon_id"));CouponInput.id(b.get("inviter_publication_revision"));}else if(!b.get("inviter_coupon_id").isNull()||!b.get("inviter_publication_revision").isNull())throw InvitationInput.bad();return b;
 }
 static JsonNode status(String raw){var b=CouponInput.parse(raw);CouponInput.exact(b,"expected_revision","status");CouponInput.version(b.get("expected_revision"));if(!b.get("status").isTextual()||!Set.of("PUBLISHED","STOPPED").contains(b.get("status").textValue()))throw InvitationInput.bad();return b;}
}
