package com.autocare.platform;

import com.autocare.platform.file.*;
import io.minio.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RealUploadAdaptersTest {
    static final Network network = Network.newNetwork();
    static Future<String> image(String name, String directory, boolean includeScript) {
        if (Boolean.getBoolean("upload.test.images.prebuilt")) return CompletableFuture.completedFuture(name);
        var image = new ImageFromDockerfile(name, false).withDockerfile(Path.of("..", "deploy", directory, "Dockerfile"));
        if (includeScript) image.withFileFromPath("initialize.sh", Path.of("..", "deploy", directory, "initialize.sh"));
        return image;
    }
    // Deliberately synthetic signatures: real engine/protocol evidence, not production coverage.
    static final byte[] INFECTED = "\u0089PNG\r\n\u001a\nAutocareScannerTestMarker".getBytes(StandardCharsets.ISO_8859_1);
    static String signature() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(INFECTED))
            + ":" + INFECTED.length + ":Autocare.Test.Malware\n"; }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Container static GenericContainer<?> minio = new GenericContainer<>(image("autocare-upload-minio-ci", "minio", false))
        .withEnv("MINIO_ROOT_USER", "ci-upload-root").withEnv("MINIO_ROOT_PASSWORD", "ci-upload-private-password")
        .withNetwork(network).withNetworkAliases("upload-minio")
        .withCommand("server", "/data").withExposedPorts(9000)
        .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));
    @Container static GenericContainer<?> clamd = new GenericContainer<>("clamav/clamav:1.4.3_base")
        .withCopyToContainer(Transferable.of(signature()), "/test-db/test.hdb")
        .withCopyToContainer(Transferable.of("Foreground yes\nUser root\nDatabaseDirectory /test-db\n"
            + "TCPSocket 3310\nTCPAddr 0.0.0.0\nStreamMaxLength 10M\nMaxFileSize 12M\nMaxScanSize 12M\n"
            + "AlertExceedsMax yes\nPidFile /tmp/clamd.pid\n"), "/test-clamd.conf")
        .withCreateContainerCmdModifier(command -> command.withEntrypoint("clamd"))
        .withCommand("--config-file=/test-clamd.conf").withExposedPorts(3310)
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
    static JdbcTemplate jdbc;
    static MinioClient admin;
    static String endpoint;
    String bucket;
    MinioPrivateObjectStore store;
    ClamdVirusScanner scanner;
    PrivateUploadService uploads;
    @BeforeAll static void schema() throws Exception {
        endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        admin = MinioClient.builder().endpoint(endpoint).credentials("ci-upload-root", "ci-upload-private-password").region("us-east-1").build();
        var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); jdbc = new JdbcTemplate(source);
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of("..","docs","sql","migrations","V001__baseline.sql")));
            for(String file:java.util.List.of("V003__auth_lifecycle.sql","V004__upload_http.sql"))
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of("..","docs","sql","migrations",file)));
        }
    }
    @BeforeEach void prepare() throws Exception {
        jdbc.update("DELETE FROM file_object"); bucket = "upload-" + UUID.randomUUID();
        admin.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        store = storage(endpoint, "ci-upload-private-password", bucket);
        scanner = new ClamdVirusScanner(clamd.getHost(), clamd.getMappedPort(3310), 1000, 5000, false);
        uploads = new PrivateUploadService(scanner, store, new JdbcFileMetadataRepository(jdbc, new DataSourceTransactionManager(jdbc.getDataSource())));
    }
    MinioPrivateObjectStore storage(String publicEndpoint, String secret, String targetBucket) {
        return new MinioPrivateObjectStore(endpoint, publicEndpoint, "ci-upload-root", secret, targetBucket, "us-east-1", true, 3000);
    }
    HttpResponse<byte[]> get(String url) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    String key(long id) { return uploads.readable(PrivateUploadTest.OWNER, id).objectKey(); }
    @Test void realCleanUploadAnonymousDenialSignedDownloadAndDelete() throws Exception {
        var file = uploads.upload(PrivateUploadTest.OWNER, "image.png", new ByteArrayInputStream(PrivateUploadTest.PNG));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM file_object", Integer.class));
        assertTrue(store.isPrivate()); String key = key(file.id());
        assertEquals(403, get(endpoint + "/" + bucket + "/" + key).statusCode());
        assertEquals(403, get(endpoint + "/" + bucket + "?list-type=2").statusCode());
        var access = new PrivateFileAccessService(uploads, store, 120).sign(PrivateUploadTest.OWNER, file.id());
        assertTrue(access.url().startsWith(endpoint)); var response = get(access.url());
        assertEquals(200, response.statusCode()); assertArrayEquals(PrivateUploadTest.PNG, response.body());
        store.delete(key); store.delete(key); assertThrows(UploadException.class, () -> store.stat(key));
    }
    @Test void realEngineRejectsInfectionWithoutMetadataOrObject() throws Exception {
        assertEquals(VirusScanner.Result.INFECTED, scanner.scan(INFECTED));
        var error = assertThrows(UploadException.class,
            () -> uploads.upload(PrivateUploadTest.OWNER, "image.png", new ByteArrayInputStream(INFECTED)));
        assertEquals(UploadException.Reason.INFECTED, error.reason());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM file_object", Integer.class));
        assertFalse(admin.listObjects(ListObjectsArgs.builder().bucket(bucket).build()).iterator().hasNext());
    }
    @Test void signedUrlActuallyExpires() throws Exception {
        var file = uploads.upload(PrivateUploadTest.OWNER, "image.png", new ByteArrayInputStream(PrivateUploadTest.PNG));
        var access = new PrivateFileAccessService(uploads, store, 1).sign(PrivateUploadTest.OWNER, file.id());
        Thread.sleep(2100); assertEquals(403, get(access.url()).statusCode());
    }
    @Test void publicPolicyRejectsUploadsAndSigning() throws Exception {
        var file = uploads.upload(PrivateUploadTest.OWNER, "image.png", new ByteArrayInputStream(PrivateUploadTest.PNG));
        admin.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config("{\"Version\":\"2012-10-17\",\"Statement\":["
            + "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + bucket + "/*\"]}]}" ).build());
        assertFalse(store.isPrivate()); assertEquals(200, get(endpoint + "/" + bucket + "/" + key(file.id())).statusCode());
        assertThrows(UploadException.class, () -> new PrivateFileAccessService(uploads, store, 120).sign(PrivateUploadTest.OWNER, file.id()));
        assertThrows(UploadException.class, () -> uploads.upload(PrivateUploadTest.OWNER,"image.png",new ByteArrayInputStream(PrivateUploadTest.PNG)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM file_object", Integer.class));
        admin.deleteBucketPolicy(DeleteBucketPolicyArgs.builder().bucket(bucket).build());
    }
    @Test void badCredentialsMissingBucketPublicEndpointAndObjectMismatchFailClosed() {
        assertThrows(UploadException.class, () -> storage(endpoint,"incorrect-private-password",bucket).isPrivate());
        assertThrows(UploadException.class, () -> storage(endpoint,"ci-upload-private-password","missing-bucket").isPrivate());
        var file = uploads.upload(PrivateUploadTest.OWNER,"image.png",new ByteArrayInputStream(PrivateUploadTest.PNG)); String key = key(file.id());
        assertThrows(UploadException.class, () -> new PrivateFileAccessService(uploads,storage(null,"ci-upload-private-password",bucket),120).sign(PrivateUploadTest.OWNER,file.id()));
        store.put(key,"image/png",new byte[]{1});
        assertThrows(UploadException.class, () -> new PrivateFileAccessService(uploads,store,120).sign(PrivateUploadTest.OWNER,file.id()));
    }
    GenericContainer<?> initializer(String account) {
        return new GenericContainer<>(image("autocare-upload-init-ci", "upload-init", true))
            .withNetwork(network).withEnv("MINIO_ENDPOINT", "http://upload-minio:9000")
            .withEnv("MINIO_ROOT_USER", "ci-upload-root").withEnv("MINIO_ROOT_PASSWORD", "ci-upload-private-password")
            .withEnv("UPLOAD_MINIO_BUCKET", bucket).withEnv("UPLOAD_MINIO_ACCESS_KEY", account)
            .withEnv("UPLOAD_MINIO_SECRET_KEY", "ci-dedicated-upload-password")
            .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(30)));
    }
    @Test void initializationCreatesDedicatedLimitedAccountAndRefusesToOverwriteIt() {
        String account = "upload-" + UUID.randomUUID();
        try (var init = initializer(account)) { init.start(); assertTrue(init.getLogs().contains("initialized")); }
        var dedicated = new MinioPrivateObjectStore(endpoint, endpoint, account, "ci-dedicated-upload-password", bucket,"us-east-1",true,3000);
        assertTrue(dedicated.isPrivate());
        String key = "uploads/" + UUID.randomUUID(); dedicated.put(key,"image/png",PrivateUploadTest.PNG);
        assertEquals(PrivateUploadTest.PNG.length,dedicated.stat(key).sizeBytes()); dedicated.delete(key);
        assertThrows(UploadException.class, () -> new MinioPrivateObjectStore(endpoint,endpoint,account,
            "ci-dedicated-upload-password","other-bucket","us-east-1",true,3000).isPrivate());
        try (var again = initializer(account)) { assertThrows(org.testcontainers.containers.ContainerLaunchException.class, again::start); }
        assertTrue(dedicated.isPrivate());
    }
    @Test void durableHttpUploadProtocolUsesRealAdaptersAndReplaysOnlyOneFile() throws Exception {
        for(String table:java.util.List.of("upload_cleanup_task","upload_request","audit_log","auth_session","user")) jdbc.update("DELETE FROM "+table);
        String session=UUID.randomUUID().toString();jdbc.update("INSERT INTO user(id,status) VALUES (1001,1)");
        jdbc.update("INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) VALUES (?,'user',1001,'OWNER','test',?,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 DAY))",session,UUID.randomUUID().toString());
        var owner=new UploadOwner(1001,session,java.time.Instant.now().plusSeconds(600));
        var requests=new JdbcUploadRequests(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()),new com.fasterxml.jackson.databind.ObjectMapper());
        var http=new UploadHttpService(requests,scanner,store);String token=UUID.randomUUID().toString();
        var result=http.upload(owner,token,"test.png",new ByteArrayInputStream(PrivateUploadTest.PNG));
        assertEquals(result,http.upload(owner,token,"different-name.png",new ByteArrayInputStream(PrivateUploadTest.PNG)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM file_object",Integer.class));
        var signed=new PrivateFileAccessService(uploads,store,120).sign(owner.actor(),result.path("data").path("file_id").asLong());
        assertArrayEquals(PrivateUploadTest.PNG,get(signed.url()).body());
        assertEquals(422,assertThrows(UploadHttpException.class,()->http.upload(owner,UUID.randomUUID().toString(),"test.png",new ByteArrayInputStream(INFECTED))).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM file_object",Integer.class));
        http.reconcile();assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM file_object",Integer.class));
    }

}
