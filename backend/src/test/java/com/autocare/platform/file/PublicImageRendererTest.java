package com.autocare.platform.file;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class PublicImageRendererTest {
 @Test void reencodesOnlyPixelDataAsPng()throws Exception{var image=new BufferedImage(4,3,BufferedImage.TYPE_INT_RGB);image.setRGB(1,1,0xff123456);var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);out.write("private trailing metadata".getBytes());var result=new PublicImageRenderer().render(out.toByteArray());assertEquals(4,result.width());assertEquals(3,result.height());assertFalse(new String(result.png(),java.nio.charset.StandardCharsets.ISO_8859_1).contains("private trailing metadata"));assertEquals(0xff123456,ImageIO.read(new ByteArrayInputStream(result.png())).getRGB(1,1));}
 @Test void rejectsInvalidAndOversizedInputs(){var r=new PublicImageRenderer();assertThrows(ResponseStatusException.class,()->r.render(new byte[]{1,2,3}));assertThrows(ResponseStatusException.class,()->r.render(new byte[FileValidator.MAX_BYTES+1]));}
 @Test void rejectsExcessiveDimensionsBeforeDecodingPixels()throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(4097,1,BufferedImage.TYPE_INT_RGB),"png",out);assertThrows(ResponseStatusException.class,()->new PublicImageRenderer().render(out.toByteArray()));}
}
