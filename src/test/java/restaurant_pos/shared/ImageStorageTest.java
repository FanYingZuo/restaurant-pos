package restaurant_pos.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.image.ImageHttp;
import restaurant_pos.shared.image.LocalImageStorage;
import restaurant_pos.shared.image.StoredImage;

/** 卡 0.9：圖片儲存介面與本機實作，§4.10 六條全部（01-PLAN §4.10）。 */
class ImageStorageTest {

    @TempDir
    Path dir;

    private LocalImageStorage storage() {
        return new LocalImageStorage(dir);
    }

    // ---- 測試圖片產生 ----

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static byte[] png(int w, int h) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF336699);
        return encode(image, "png");
    }

    private static byte[] jpeg(int w, int h) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x336699);
        return encode(image, "jpg");
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) (data.length >>> 24), (byte) (data.length >>> 16),
                (byte) (data.length >>> 8), (byte) data.length});
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        long v = crc.getValue();
        out.writeBytes(new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v});
        return out.toByteArray();
    }

    /** 只有標頭、宣稱很大尺寸的 PNG：若先完整解碼會爆記憶體或很慢。 */
    private static byte[] headerOnlyPng(int w, int h) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        byte[] ihdr = {(byte) (w >>> 24), (byte) (w >>> 16), (byte) (w >>> 8), (byte) w,
                (byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h, 8, 2, 0, 0, 0};
        out.writeBytes(chunk("IHDR", ihdr));
        out.writeBytes(chunk("IEND", new byte[0]));
        return out.toByteArray();
    }

    private static byte[] jpegWithExif(byte[] jpeg, String secret) {
        byte[] payload = ("Exif\0\0" + secret).getBytes(StandardCharsets.ISO_8859_1);
        int length = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {jpeg[0], jpeg[1]});
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >>> 8), (byte) length});
        out.writeBytes(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] pngWithText(byte[] png, String secret) {
        int afterIhdr = 8 + 12 + 13;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, afterIhdr);
        out.writeBytes(chunk("tEXt", ("Comment\0" + secret).getBytes(StandardCharsets.ISO_8859_1)));
        out.write(png, afterIhdr, png.length - afterIhdr);
        return out.toByteArray();
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }

    private static void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_IMAGE);
    }

    // ---- 第 1 條：檔案 ≤ 2 MB ----

    @Test
    void 超過2MB拒絕_空內容與null也拒絕() throws IOException {
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(png(1, 1), 0, big, 0, 8);
        assertInvalid(() -> storage().replace(big, null));
        assertInvalid(() -> storage().replace(new byte[0], null));
        assertInvalid(() -> storage().replace(null, null));
    }

    @Test
    void 剛好2MB以內的有效圖片可以儲存() throws IOException {
        byte[] ok = png(10, 10);
        assertThat(ok.length).isLessThanOrEqualTo(2 * 1024 * 1024);
        assertThat(storage().replace(ok, null)).matches("^[0-9a-f]{32}\\.png$");
    }

    // ---- 第 2 條：以開頭位元組判斷 JPEG／PNG ----

    @Test
    void JPEG與PNG都可儲存並讀回正確類型() throws IOException {
        LocalImageStorage storage = storage();
        String jpgKey = storage.replace(jpeg(20, 10), null);
        String pngKey = storage.replace(png(20, 10), null);
        assertThat(jpgKey).endsWith(".jpg");
        assertThat(pngKey).endsWith(".png");
        StoredImage jpg = storage.load(jpgKey);
        StoredImage png = storage.load(pngKey);
        assertThat(jpg.contentType()).isEqualTo("image/jpeg");
        assertThat(png.contentType()).isEqualTo("image/png");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpg.content()));
        assertThat(decoded.getWidth()).isEqualTo(20);
        assertThat(decoded.getHeight()).isEqualTo(10);
    }

    @Test
    void 不信任內容類型_非JPEG與PNG的內容一律拒絕() throws IOException {
        LocalImageStorage storage = storage();
        assertInvalid(() -> storage.replace("這是文字檔，不是圖片".getBytes(StandardCharsets.UTF_8), null));
        assertInvalid(() -> storage.replace(encodeQuiet(new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB), "gif"), null));
        assertInvalid(() -> storage.replace(encodeQuiet(new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB), "bmp"), null));
        // 有 PNG 開頭但內容損毀
        byte[] fake = new byte[200];
        System.arraycopy(png(1, 1), 0, fake, 0, 8);
        assertInvalid(() -> storage.replace(fake, null));
        // 有 JPEG 開頭但內容損毀
        byte[] fakeJpeg = new byte[200];
        fakeJpeg[0] = (byte) 0xFF;
        fakeJpeg[1] = (byte) 0xD8;
        fakeJpeg[2] = (byte) 0xFF;
        assertInvalid(() -> storage.replace(fakeJpeg, null));
    }

    private static byte[] encodeQuiet(BufferedImage image, String format) {
        try {
            return encode(image, format);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- 第 3 條：先讀標頭寬高，超過 4000 像素直接拒絕，再完整解碼 ----

    @Test
    void 寬或高超過4000像素拒絕_剛好4000可以() throws IOException {
        LocalImageStorage storage = storage();
        assertInvalid(() -> storage.replace(encodeQuiet(new BufferedImage(4001, 1, BufferedImage.TYPE_INT_ARGB), "png"), null));
        assertInvalid(() -> storage.replace(encodeQuiet(new BufferedImage(1, 4001, BufferedImage.TYPE_INT_ARGB), "png"), null));
        assertThat(storage.replace(encodeQuiet(new BufferedImage(4000, 2, BufferedImage.TYPE_INT_ARGB), "png"), null))
                .isNotNull();
    }

    @Test
    void 只憑標頭就拒絕超大尺寸_不會先完整解碼() {
        long start = System.nanoTime();
        assertInvalid(() -> storage().replace(headerOnlyPng(60000, 60000), null));
        assertThat((System.nanoTime() - start) / 1_000_000).as("毫秒").isLessThan(2000);
    }

    // ---- 第 4 條：重新編碼、去除 EXIF ----

    @Test
    void JPEG的EXIF被去除() throws IOException {
        String secret = "SECRET-EXIF-LOCATION-DATA";
        byte[] withExif = jpegWithExif(jpeg(16, 16), secret);
        assertThat(contains(withExif, secret)).isTrue();
        LocalImageStorage storage = storage();
        String key = storage.replace(withExif, null);
        byte[] stored = storage.load(key).content();
        assertThat(contains(stored, secret)).isFalse();
        assertThat(contains(stored, "Exif")).isFalse();
        assertThat(ImageIO.read(new ByteArrayInputStream(stored))).isNotNull();
    }

    @Test
    void PNG的文字中繼資料被去除() throws IOException {
        String secret = "SECRET-PNG-METADATA";
        byte[] withText = pngWithText(png(16, 16), secret);
        assertThat(contains(withText, secret)).isTrue();
        LocalImageStorage storage = storage();
        byte[] stored = storage.load(storage.replace(withText, null)).content();
        assertThat(contains(stored, secret)).isFalse();
        assertThat(ImageIO.read(new ByteArrayInputStream(stored))).isNotNull();
    }

    // ---- 第 5 條：檔名由程式產生、鍵格式驗證 ----

    @Test
    void 檔名由程式產生_每次不同() throws IOException {
        LocalImageStorage storage = storage();
        byte[] same = png(8, 8);
        String a = storage.replace(same, null);
        String b = storage.replace(same, null);
        assertThat(a).isNotEqualTo(b).matches("^[0-9a-f]{32}\\.png$");
        assertThat(Files.exists(dir.resolve(a))).isTrue();
    }

    @Test
    void 讀取不合法的鍵一律NOT_FOUND_包含路徑穿越() throws IOException {
        LocalImageStorage storage = storage();
        Files.writeString(dir.getParent().resolve("outside.png"), "x");
        for (String key : new String[] {"../outside.png", "..\\outside.png", "/etc/passwd", "a/b.png",
                "ABCDEF0123456789ABCDEF0123456789.png", "abc.png", "00000000000000000000000000000000.gif",
                "00000000000000000000000000000000.png.tmp", "", " ", null}) {
            assertThatThrownBy(() -> storage.load(key)).as(String.valueOf(key)).isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
        }
    }

    @Test
    void 格式正確但檔案不存在也是NOT_FOUND() {
        assertThatThrownBy(() -> storage().load("00000000000000000000000000000000.png"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 刪除不合法的鍵不動作_不會刪到目錄外的檔案() throws IOException {
        Path outside = dir.getParent().resolve("keep-me.png");
        Files.writeString(outside, "x");
        storage().delete("../keep-me.png");
        storage().delete(null);
        assertThat(Files.exists(outside)).isTrue();
    }

    // ---- 第 6 條：新圖成功才刪舊檔；刪檔失敗只記日誌 ----

    @Test
    void 新圖儲存成功後刪除舊檔() throws IOException {
        LocalImageStorage storage = storage();
        String oldKey = storage.replace(png(8, 8), null);
        String newKey = storage.replace(png(9, 9), oldKey);
        assertThat(Files.exists(dir.resolve(oldKey))).isFalse();
        assertThat(Files.exists(dir.resolve(newKey))).isTrue();
    }

    @Test
    void 新圖不合法時舊檔保留() throws IOException {
        LocalImageStorage storage = storage();
        String oldKey = storage.replace(png(8, 8), null);
        assertInvalid(() -> storage.replace("壞掉".getBytes(StandardCharsets.UTF_8), oldKey));
        assertThat(Files.exists(dir.resolve(oldKey))).isTrue();
        try (var files = Files.list(dir)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void 刪舊檔失敗只記日誌_不影響新圖結果() throws IOException {
        LocalImageStorage failing = new LocalImageStorage(dir) {
            @Override
            protected void doDelete(Path path) throws IOException {
                throw new IOException("磁碟忙碌 secret-path-detail");
            }
        };
        Logger logger = (Logger) LoggerFactory.getLogger(LocalImageStorage.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String oldKey = failing.replace(png(8, 8), null);
            String newKey = failing.replace(png(9, 9), oldKey);
            assertThat(newKey).isNotNull();
            assertThat(Files.exists(dir.resolve(newKey))).isTrue();
            assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN);
            assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("secret-path-detail"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void 圖片回應帶禁止猜測內容類型與可快取標頭() {
        HttpHeaders headers = ImageHttp.headers("image/png");
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst(HttpHeaders.CACHE_CONTROL)).contains("public").contains("max-age=86400");
        assertThat(headers.getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("image/png");
    }

    @Test
    void 重新編碼後內容與上傳內容不同() throws IOException {
        LocalImageStorage storage = storage();
        byte[] original = jpeg(32, 32);
        byte[] stored = storage.load(storage.replace(original, null)).content();
        assertThat(stored).isNotEqualTo(original);
    }
}
