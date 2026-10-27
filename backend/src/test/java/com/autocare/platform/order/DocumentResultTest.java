package com.autocare.platform.order;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DocumentResultTest {
 final ObjectMapper mapper=new ObjectMapper();
 ObjectNode result(){return (ObjectNode)JdbcDocumentJobsTest.answer(mapper,1);}
 JsonNode validate(JsonNode r){return DocumentResult.validate(r,"application/pdf",result().path("content_sha256").asText(),1);}
 @Test void sourceProjectionIsCopiedAndNativeConfidenceRemainsUnknown(){var r=result();var copy=validate(r);r.put("status","EMPTY");assertEquals("EXTRACTED",copy.path("status").asText());assertTrue(copy.path("pages").get(0).path("blocks").get(0).path("confidence").isNull());}
 @Test void strictMetadataAndNoPartialResult(){
  List<Consumer<ObjectNode>> mutations=List.of(r->r.put("version",true),r->r.put("content_sha256","b".repeat(64)),r->r.put("page_count",2),r->r.put("all_pages_processed",false),r->r.put("image_recognition_performed",true),r->r.put("raw_text","private"),r->r.put("source","MOCK"),r->r.put("content_type","image/png"));
  for(var mutate:mutations){var r=result();mutate.accept(r);assertThrows(IllegalArgumentException.class,()->validate(r));}
 }
 @Test void nativeBlockOffsetsAndCoverageAreExact(){
  for(String kind:List.of("offset","missing","confidence","control","surrogate","extra")){
   var r=result();var page=(ObjectNode)r.path("pages").get(0);var block=(ObjectNode)page.path("blocks").get(0);
   switch(kind){case "offset"->((ObjectNode)block.path("source")).put("end",5);case "missing"->page.putArray("blocks");case "confidence"->block.put("confidence",.9);case "control"->page.put("text","a\u0000");case "surrogate"->page.put("text","\ud800");case "extra"->block.put("private","secret");}
   assertThrows(IllegalArgumentException.class,()->validate(r));
  }
 }
 ObjectNode ocr(){var r=result();r.put("image_recognition_performed",true);var page=(ObjectNode)r.path("pages").get(0);page.put("method","OCR");page.putArray("raster_size").add(100).add(50);var block=(ObjectNode)page.path("blocks").get(0);block.put("confidence",.73);var source=block.putObject("source").put("kind","RASTER_POLYGON");var points=source.putArray("points");for(int[] point:new int[][]{{1,1},{90,1},{90,40},{1,40}})points.addArray().add(point[0]).add(point[1]);return r;}
 @Test void ocrUsesActualConvexBoundedGeometry(){assertDoesNotThrow(()->validate(ocr()));for(String kind:List.of("unknown","out","cross","raster")){
  var r=ocr();var page=(ObjectNode)r.path("pages").get(0);var block=(ObjectNode)page.path("blocks").get(0);var points=(ArrayNode)block.path("source").path("points");
  switch(kind){case "unknown"->block.putNull("confidence");case "out"->((ArrayNode)points.get(0)).set(0,IntNode.valueOf(101));case "cross"->points.set(2,mapper.valueToTree(List.of(0,0)));case "raster"->page.putArray("raster_size").add(3000).add(2001);}
  assertThrows(IllegalArgumentException.class,()->validate(r));
 }}
}
