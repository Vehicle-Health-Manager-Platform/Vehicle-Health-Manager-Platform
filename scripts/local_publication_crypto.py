"""Prepare bcrypt-only synthetic OTP helper from the existing built JAR, offline."""
from pathlib import Path
from zipfile import ZipFile

target = Path('.cache/a7-publication-crypto')
target.mkdir(parents=True, exist_ok=True)
with ZipFile('backend/target/platform-backend-0.1.0-SNAPSHOT.jar') as archive:
    for name in archive.namelist():
        if name.startswith(('BOOT-INF/lib/spring-security-crypto-', 'BOOT-INF/lib/spring-jcl-')):
            (target / Path(name).name).write_bytes(archive.read(name))
(target / 'BCryptFixture.java').write_text('''import java.util.Scanner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
class BCryptFixture {
 public static void main(String[] args) {
  var input=new Scanner(System.in);var encoder=new BCryptPasswordEncoder();
  while(input.hasNextLine())System.out.println(encoder.encode(input.nextLine()));
 }
}
''', encoding='utf-8')
print('Synthetic bcrypt helper prepared; no credentials generated or printed')
