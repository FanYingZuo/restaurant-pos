package restaurant_pos.shared.image;

/** 已儲存的圖片內容；contentType 只會是 image/jpeg 或 image/png。 */
public record StoredImage(byte[] content, String contentType) {
}
