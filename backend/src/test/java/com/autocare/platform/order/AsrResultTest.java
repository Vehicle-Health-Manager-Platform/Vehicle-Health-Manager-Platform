package com.autocare.platform.order;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AsrResultTest {
 final ObjectMapper mapper=new ObjectMapper();
 @Test void exact_duration_unknown_confidence_and_immutable_copy(){var value=JdbcAsrJobsTest.answer(mapper,1080);var copied=AsrResult.validate(value,1080);((com.fasterxml.jackson.databind.node.ObjectNode)value.path("segments").get(0)).put("text","changed");assertNotEquals(value,copied);assertTrue(copied.path("segments").get(0).path("confidence").isNull());}
 @Test void bad_flags_extras_timestamps_and_scores_refused(){for(String field:new String[]{"duration_ms","requires_confirmation","source","segments"}){var value=(com.fasterxml.jackson.databind.node.ObjectNode)JdbcAsrJobsTest.answer(mapper,1080);value.remove(field);assertThrows(IllegalArgumentException.class,()->AsrResult.validate(value,1080));}for(String text:new String[]{" ","x\0","\ud800","x".repeat(501)}){var value=JdbcAsrJobsTest.answer(mapper,1080);((com.fasterxml.jackson.databind.node.ObjectNode)value.path("segments").get(0)).put("text",text);assertThrows(IllegalArgumentException.class,()->AsrResult.validate(value,1080));}}
 @Test void empty_language_and_exact_flags_do_not_promote_result(){
  var empty=(com.fasterxml.jackson.databind.node.ObjectNode)JdbcAsrJobsTest.answer(mapper,1080);empty.put("status","EMPTY");empty.putNull("language");empty.putArray("segments");assertEquals("EMPTY",AsrResult.validate(empty,1080).path("status").asText());
  empty.put("status","TRANSCRIBED");assertThrows(IllegalArgumentException.class,()->AsrResult.validate(empty,1080));
  for(String name:new String[]{"requires_confirmation","audio_recognition_performed"}){var value=(com.fasterxml.jackson.databind.node.ObjectNode)JdbcAsrJobsTest.answer(mapper,1080);value.put(name,false);assertThrows(IllegalArgumentException.class,()->AsrResult.validate(value,1080));}
  var value=(com.fasterxml.jackson.databind.node.ObjectNode)JdbcAsrJobsTest.answer(mapper,1080);value.put("raw_audio","not-allowed");assertThrows(IllegalArgumentException.class,()->AsrResult.validate(value,1080));
 }
 @Test void time_ranges_segments_and_utf16_budget_are_strict(){
  for(int[] time:new int[][]{{-1,900},{1,1},{0,1081}}){var value=JdbcAsrJobsTest.answer(mapper,1080);var row=(com.fasterxml.jackson.databind.node.ObjectNode)value.path("segments").get(0);row.put("start_ms",time[0]);row.put("end_ms",time[1]);assertThrows(IllegalArgumentException.class,()->AsrResult.validate(value,1080));}
  var value=(com.fasterxml.jackson.databind.node.ObjectNode)JdbcAsrJobsTest.answer(mapper,1080);var rows=value.putArray("segments");rows.addObject().put("start_ms",0).put("end_ms",400).put("text","x".repeat(500)).putNull("confidence");rows.addObject().put("start_ms",400).put("end_ms",900).put("text","y".repeat(499)).putNull("confidence");assertEquals(2,AsrResult.validate(value,1080).path("segments").size());
  ((com.fasterxml.jackson.databind.node.ObjectNode)rows.get(1)).put("text","y".repeat(500));assertThrows(IllegalArgumentException.class,()->AsrResult.validate(value,1080));
  rows.removeAll();for(int i=0;i<51;i++)rows.addObject().put("start_ms",i).put("end_ms",i+1).put("text","x").putNull("confidence");assertThrows(IllegalArgumentException.class,()->AsrResult.validate(value,1080));
 }
 @Test void true_confidence_or_unknown_only_never_logprob_or_nonfinite(){
  for(double score:new double[]{-.1,1.1,Double.NaN,Double.POSITIVE_INFINITY}){var value=JdbcAsrJobsTest.answer(mapper,1080);((com.fasterxml.jackson.databind.node.ObjectNode)value.path("segments").get(0)).put("confidence",score);assertThrows(IllegalArgumentException.class,()->AsrResult.validate(value,1080));}
  var value=JdbcAsrJobsTest.answer(mapper,1080);var row=(com.fasterxml.jackson.databind.node.ObjectNode)value.path("segments").get(0);row.put("confidence",true);assertThrows(IllegalArgumentException.class,()->AsrResult.validate(value,1080));row.putNull("confidence");row.put("text","😀".repeat(250));AsrResult.validate(value,1080);row.put("text","😀".repeat(251));assertThrows(IllegalArgumentException.class,()->AsrResult.validate(value,1080));
 }
}
