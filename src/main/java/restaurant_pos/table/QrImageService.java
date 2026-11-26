package restaurant_pos.table;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Service;

/** QR 圖片（ZXing，S4）：只用 core 與 javase。輸入文字不會被記錄（內含顧客 token）。 */
@Service
public class QrImageService {

    private static final int SIZE = 400;

    public byte[] png(String text) {
        try {
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 2,
                    EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, SIZE, SIZE, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("產生 QR 圖片失敗", e);
        }
    }
}
