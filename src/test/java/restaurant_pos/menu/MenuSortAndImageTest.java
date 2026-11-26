package restaurant_pos.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/** 卡 3.6、3.7：排序、圖片上傳／刪除／取圖（01-PLAN S3、§4.10）。 */
class MenuSortAndImageTest extends MenuTestBase {

    private MvcResult reorder(String url, List<? extends Number> ids) throws Exception {
        return call(put(url), "{\"ids\":" + ids + "}");
    }

    private List<Long> categoryOrder() throws Exception {
        List<Long> ids = new ArrayList<>();
        tree().get("categories").forEach(c -> ids.add(c.get("id").asLong()));
        return ids;
    }

    // ---- 3.6 排序 ----

    @Test
    void 分類排序_依位置從0重新編號() throws Exception {
        long a = category("甲");
        long b = category("乙");
        long c = category("丙");
        jdbc.update("UPDATE menu_category SET display_order = id * 10 + 5"); // 刻意弄成不連續
        assertThat(status(reorder("/api/pos/menu/categories/order", List.of(c, a, b)))).isEqualTo(204);
        assertThat(categoryOrder()).containsExactly(c, a, b);
        assertThat(jdbc.queryForList("SELECT display_order FROM menu_category ORDER BY id", Integer.class))
                .containsExactly(1, 2, 0); // a=1、b=2、c=0
    }

    @Test
    void 排序列表必須剛好等於該層所有未刪除項目() throws Exception {
        long a = category("甲");
        long b = category("乙");
        long gone = category("已刪");
        call(delete("/api/pos/menu/categories/" + gone));
        String url = "/api/pos/menu/categories/order";
        for (List<Long> bad : List.of(List.of(a), List.of(a, b, 999999L), List.of(a, a, b), List.of(a, b, gone), List.<Long>of())) {
            MvcResult r = reorder(url, bad);
            assertThat(status(r)).as(bad.toString()).isEqualTo(400);
            assertThat(errorCode(r)).as(bad.toString()).isEqualTo("INVALID_SORT_REQUEST");
        }
        assertThat(categoryOrder()).containsExactly(a, b); // 沒有任何改變
        assertThat(errorCode(call(put(url), "{}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(errorCode(call(put(url), "{\"ids\":[1,null]}"))).isEqualTo("INVALID_SORT_REQUEST");
        assertThat(status(reorder(url, List.of(b, a)))).isEqualTo(204);
    }

    @Test
    void 沒有任何分類時空列表合法() throws Exception {
        assertThat(status(reorder("/api/pos/menu/categories/order", List.<Long>of()))).isEqualTo(204);
    }

    @Test
    void 品項排序_只限該分類() throws Exception {
        long cat = category("麵食");
        long other = category("飲料");
        long i1 = item(cat, "一", 10, "K1", true);
        long i2 = item(cat, "二", 10, "K1", true);
        long i3 = item(cat, "三", 10, "K1", true);
        long foreign = item(other, "別類", 10, "K1", true);
        assertThat(status(reorder("/api/pos/menu/categories/" + cat + "/items/order", List.of(i3, i1, i2)))).isEqualTo(204);
        List<String> names = new ArrayList<>();
        tree().get("categories").get(0).get("items").forEach(n -> names.add(n.get("name").asText()));
        assertThat(names).containsExactly("三", "一", "二");
        MvcResult mixed = reorder("/api/pos/menu/categories/" + cat + "/items/order", List.of(i3, i1, i2, foreign));
        assertThat(errorCode(mixed)).isEqualTo("INVALID_SORT_REQUEST");
        assertThat(errorCode(reorder("/api/pos/menu/categories/999999/items/order", List.<Long>of()))).isEqualTo("MENU_CATEGORY_NOT_FOUND");
    }

    @Test
    void 群組與選項排序() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        long g1 = group(it, "甲", 0, 1, false);
        long g2 = group(it, "乙", 0, 1, false);
        long m1 = modifier(g1, "一", 0);
        long m2 = modifier(g1, "二", 0);
        assertThat(status(reorder("/api/pos/menu/items/" + it + "/modifier-groups/order", List.of(g2, g1)))).isEqualTo(204);
        assertThat(status(reorder("/api/pos/menu/modifier-groups/" + g1 + "/modifiers/order", List.of(m2, m1)))).isEqualTo(204);
        JsonNode groups = findItem(tree(), it).get("modifierGroups");
        assertThat(groups.get(0).get("id").asLong()).isEqualTo(g2);
        assertThat(groups.get(1).get("modifiers").get(0).get("id").asLong()).isEqualTo(m2);
        assertThat(errorCode(reorder("/api/pos/menu/items/" + it + "/modifier-groups/order", List.of(g1)))).isEqualTo("INVALID_SORT_REQUEST");
        assertThat(errorCode(reorder("/api/pos/menu/modifier-groups/" + g1 + "/modifiers/order", List.of(m1, 999999L)))).isEqualTo("INVALID_SORT_REQUEST");
        assertThat(errorCode(reorder("/api/pos/menu/items/999999/modifier-groups/order", List.<Long>of()))).isEqualTo("MENU_ITEM_NOT_FOUND");
        assertThat(errorCode(reorder("/api/pos/menu/modifier-groups/999999/modifiers/order", List.<Long>of()))).isEqualTo("MODIFIER_GROUP_NOT_FOUND");
    }

    @Test
    void 刪除後的項目不在排序範圍內() throws Exception {
        long cat = category("麵食");
        long i1 = item(cat, "一", 10, "K1", false);
        long i2 = item(cat, "二", 10, "K1", false);
        call(delete("/api/pos/menu/items/" + i1));
        assertThat(status(reorder("/api/pos/menu/categories/" + cat + "/items/order", List.of(i2)))).isEqualTo(204);
        assertThat(errorCode(reorder("/api/pos/menu/categories/" + cat + "/items/order", List.of(i1, i2)))).isEqualTo("INVALID_SORT_REQUEST");
    }

    // ---- 3.7 圖片 ----

    private static byte[] image(String format, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0x336699);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private MvcResult upload(long itemId, byte[] content) throws Exception {
        return mvc.perform(multipart(HttpMethod.PUT, "/api/pos/menu/items/" + itemId + "/image")
                .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", content))
                .header("X-Device-Token", pos.token())).andReturn();
    }

    private boolean fileExists(String key) {
        return Files.exists(Path.of(IMAGE_DIR.toString(), key));
    }

    @Test
    void 上傳JPEG回傳圖片鍵_檔案存在_可公開取圖() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        MvcResult r = upload(it, image("jpg", 40, 30));
        assertThat(status(r)).isEqualTo(200);
        String key = body(r).get("imageKey").asText();
        assertThat(key).matches("^[0-9a-f]{32}\\.jpg$");
        assertThat(fileExists(key)).isTrue();
        assertThat(jdbc.queryForObject("SELECT image_key FROM menu_item WHERE id = ?", String.class, it)).isEqualTo(key);

        // 取圖不需要任何驗證，且帶禁止猜測內容類型與可快取標頭
        MvcResult img = mvc.perform(get("/api/customer/images/" + key)).andReturn();
        assertThat(status(img)).isEqualTo(200);
        assertThat(img.getResponse().getContentType()).isEqualTo("image/jpeg");
        assertThat(img.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(img.getResponse().getHeader("Cache-Control")).contains("public").contains("max-age=86400");
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(img.getResponse().getContentAsByteArray()));
        assertThat(decoded.getWidth()).isEqualTo(40);
    }

    @Test
    void 上傳PNG() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        MvcResult r = upload(it, image("png", 10, 10));
        assertThat(body(r).get("imageKey").asText()).endsWith(".png");
        MvcResult img = mvc.perform(get("/api/customer/images/" + body(r).get("imageKey").asText())).andReturn();
        assertThat(img.getResponse().getContentType()).isEqualTo("image/png");
    }

    @Test
    void 新圖儲存成功後才刪舊檔() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        String oldKey = body(upload(it, image("jpg", 20, 20))).get("imageKey").asText();
        String newKey = body(upload(it, image("png", 20, 20))).get("imageKey").asText();
        assertThat(newKey).isNotEqualTo(oldKey);
        assertThat(fileExists(newKey)).isTrue();
        assertThat(fileExists(oldKey)).isFalse();
        assertThat(status(mvc.perform(get("/api/customer/images/" + oldKey)).andReturn())).isEqualTo(404);
        assertThat(status(mvc.perform(get("/api/customer/images/" + newKey)).andReturn())).isEqualTo(200);
    }

    @Test
    void 不合法的新圖_舊圖與資料都不變() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        String oldKey = body(upload(it, image("jpg", 20, 20))).get("imageKey").asText();
        MvcResult bad = upload(it, "這不是圖片".getBytes(StandardCharsets.UTF_8));
        assertThat(status(bad)).isEqualTo(400);
        assertThat(errorCode(bad)).isEqualTo("INVALID_IMAGE");
        assertThat(fileExists(oldKey)).isTrue();
        assertThat(jdbc.queryForObject("SELECT image_key FROM menu_item WHERE id = ?", String.class, it)).isEqualTo(oldKey);
    }

    @Test
    void 超過2MB與超大尺寸回INVALID_IMAGE() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        byte[] big = new byte[3 * 1024 * 1024];
        System.arraycopy(image("png", 2, 2), 0, big, 0, 8);
        assertThat(errorCode(upload(it, big))).isEqualTo("INVALID_IMAGE");
        byte[] huge = new byte[12 * 1024 * 1024]; // 超過 multipart 上限
        System.arraycopy(image("png", 2, 2), 0, huge, 0, 8);
        assertThat(errorCode(upload(it, huge))).isEqualTo("INVALID_IMAGE");
        assertThat(errorCode(upload(it, image("png", 4001, 1)))).isEqualTo("INVALID_IMAGE");
        assertThat(jdbc.queryForObject("SELECT image_key FROM menu_item WHERE id = ?", String.class, it)).isNull();
    }

    @Test
    void 不信任內容類型_GIF偽裝成JPEG被拒() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        assertThat(errorCode(upload(it, image("gif", 5, 5)))).isEqualTo("INVALID_IMAGE");
    }

    @Test
    void 品項不存在回404_且不留下檔案() throws Exception {
        long before = Files.list(IMAGE_DIR).count();
        MvcResult r = upload(999999, image("jpg", 5, 5));
        assertThat(status(r)).isEqualTo(404);
        assertThat(errorCode(r)).isEqualTo("MENU_ITEM_NOT_FOUND");
        assertThat(Files.list(IMAGE_DIR).count()).isEqualTo(before);
    }

    @Test
    void 缺少檔案欄位回VALIDATION_ERROR() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        MvcResult r = mvc.perform(multipart(HttpMethod.PUT, "/api/pos/menu/items/" + it + "/image")
                .header("X-Device-Token", pos.token())).andReturn();
        assertThat(status(r)).isEqualTo(400);
        assertThat(errorCode(r)).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 刪除圖片_清除鍵並刪檔_重複刪除不報錯() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        String key = body(upload(it, image("jpg", 20, 20))).get("imageKey").asText();
        MvcResult r = call(delete("/api/pos/menu/items/" + it + "/image"));
        assertThat(status(r)).isEqualTo(200);
        assertThat(body(r).get("imageKey").isNull()).isTrue();
        assertThat(fileExists(key)).isFalse();
        assertThat(status(call(delete("/api/pos/menu/items/" + it + "/image")))).isEqualTo(200);
        assertThat(errorCode(call(delete("/api/pos/menu/items/999999/image")))).isEqualTo("MENU_ITEM_NOT_FOUND");
    }

    @Test
    void 取圖的鍵格式驗證_防路徑穿越() throws Exception {
        MvcResult encodedSlash = mvc.perform(get("/api/customer/images/..%2Fapplication.yml")).andReturn();
        assertThat(status(encodedSlash)).isEqualTo(400); // 編碼斜線在防火牆就被拒絕
        assertThat(errorCode(encodedSlash)).isEqualTo("VALIDATION_ERROR");
        for (String key : new String[] {"not-a-key", "ABCDEF.jpg", "00000000000000000000000000000000.jpg"}) {
            MvcResult r = mvc.perform(get("/api/customer/images/" + key)).andReturn();
            assertThat(status(r)).as(key).isEqualTo(404);
        }
    }

    @Test
    void 顧客菜單帶出圖片鍵() throws Exception {
        long it = item(category("麵食"), "牛肉麵", 150, "K1", true);
        String key = body(upload(it, image("jpg", 20, 20))).get("imageKey").asText();
        JsonNode item = body(mvc.perform(get("/api/customer/menu")).andReturn()).get("categories").get(0).get("items").get(0);
        assertThat(item.get("imageKey").asText()).isEqualTo(key);
    }
}
