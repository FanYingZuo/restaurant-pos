package restaurant_pos.shared.image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;

/**
 * 本機檔案實作（§4.10）：
 * 1. 檔案 ≤ 2 MB；2. 以檔案開頭位元組判斷 JPEG／PNG；3. 先讀標頭寬高（>4000 像素拒絕）再完整解碼；
 * 4. 重新編碼後儲存（去除 EXIF 等中繼資料）；5. 檔名由程式產生，讀取與刪除前先驗證鍵格式；
 * 6. 新圖儲存成功後才刪舊檔，刪檔失敗只記日誌。
 */
public class LocalImageStorage implements ImageStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalImageStorage.class);

    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_DIMENSION = 4000;
    /** 圖片鍵：32 位小寫十六進位 ＋ 副檔名。 */
    static final Pattern KEY_PATTERN = Pattern.compile("^[0-9a-f]{32}\\.(jpg|png)$");

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private final Path root;

    public LocalImageStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("無法建立圖片目錄", e);
        }
    }

    @Override
    public String replace(byte[] content, String oldKey) {
        String newKey = storeNew(content);
        if (oldKey != null && !oldKey.equals(newKey)) {
            delete(oldKey);
        }
        return newKey;
    }

    @Override
    public StoredImage load(String key) {
        Path path = resolve(key);
        if (path == null || !Files.isRegularFile(path)) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        try {
            String type = key.endsWith(".png") ? "image/png" : "image/jpeg";
            return new StoredImage(Files.readAllBytes(path), type);
        } catch (IOException e) {
            log.warn("讀取圖片失敗：{}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
    }

    @Override
    public void delete(String key) {
        Path path = resolve(key);
        if (path == null) {
            return;
        }
        try {
            doDelete(path);
        } catch (IOException | RuntimeException e) {
            // 刪檔失敗只記日誌，不影響結果（§4.10 第 6 條）
            log.warn("刪除圖片失敗：{}", e.getClass().getSimpleName());
        }
    }

    /** 實際刪檔；獨立成方法供測試模擬失敗。 */
    protected void doDelete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    // ---- 內部 ----

    private String storeNew(byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_BYTES) {
            throw invalid();
        }
        String format = detectFormat(content);
        byte[] encoded = reencode(content, format);
        String key = UUID.randomUUID().toString().replace("-", "") + ("png".equals(format) ? ".png" : ".jpg");
        Path target = root.resolve(key);
        Path temp = root.resolve(key + ".tmp");
        try {
            Files.write(temp, encoded);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 清理失敗不影響錯誤回報
            }
            log.error("儲存圖片失敗：{}", e.getClass().getSimpleName());
            throw new IllegalStateException("儲存圖片失敗", e);
        }
        return key;
    }

    /** 以開頭位元組判斷格式，不信任副檔名與內容類型。 */
    private static String detectFormat(byte[] content) {
        if (startsWith(content, JPEG_MAGIC)) {
            return "jpeg";
        }
        if (startsWith(content, PNG_MAGIC)) {
            return "png";
        }
        throw invalid();
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        if (content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** 先讀標頭寬高（超過上限直接拒絕），再完整解碼並重新編碼，丟掉所有中繼資料。 */
    private static byte[] reencode(byte[] content, String format) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (input == null || !readers.hasNext()) {
                throw invalid();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw invalid();
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw invalid();
                }
                return write(image, format);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            throw invalid();
        }
    }

    private static byte[] write(BufferedImage image, String format) throws IOException {
        BufferedImage output = image;
        if ("jpeg".equals(format) && image.getType() != BufferedImage.TYPE_INT_RGB) {
            // JPEG 不支援透明與部分色彩模型：轉成 RGB
            output = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            output.createGraphics().drawImage(image, 0, 0, java.awt.Color.WHITE, null);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(output, format, out)) {
            throw invalid();
        }
        return out.toByteArray();
    }

    /** 驗證鍵格式後才組路徑，防路徑穿越；不合法回 null。 */
    private Path resolve(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            return null;
        }
        Path path = root.resolve(key).normalize();
        return path.startsWith(root) ? path : null;
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_IMAGE);
    }
}
