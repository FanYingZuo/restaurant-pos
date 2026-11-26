package restaurant_pos.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 錯誤碼全集與 HTTP 狀態對照（docs/plan/01-PLAN.md §4.8）。本列舉以外不得新增錯誤碼。
 * 訊息為固定繁體中文，不含任何動態資料或機密值。
 */
public enum ErrorCode {

    // 通用
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "輸入資料格式不正確"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "找不到資源"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "不支援的請求方法"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支援的內容類型"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "沒有權限執行此操作"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "資料已被更新，請重新操作"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系統發生未預期的錯誤"),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "重複的請求"),

    // 裝置
    DEVICE_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "裝置憑證無效"),
    DEVICE_TYPE_FORBIDDEN(HttpStatus.FORBIDDEN, "此裝置類型不可使用此功能"),
    PAIRING_CODE_INVALID(HttpStatus.FORBIDDEN, "配對碼無效"),
    PAIRING_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "配對嘗試次數過多，請稍後再試"),
    DEVICE_SELF_REVOKE(HttpStatus.FORBIDDEN, "不可撤銷目前使用中的裝置"),

    // 員工與打卡
    INVALID_PIN(HttpStatus.FORBIDDEN, "PIN 錯誤"),
    PIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "PIN 嘗試次數過多，請稍後再試"),
    NOT_MANAGER(HttpStatus.FORBIDDEN, "此操作需要管理者"),
    ALREADY_CLOCKED_IN(HttpStatus.CONFLICT, "已經在上班中"),
    CLOCK_TOO_FAST(HttpStatus.TOO_MANY_REQUESTS, "打卡間隔太短，請稍後再試"),
    EMPLOYEE_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到員工"),
    PIN_ALREADY_USED(HttpStatus.CONFLICT, "此 PIN 已被使用"),
    LAST_MANAGER(HttpStatus.CONFLICT, "必須至少保留一位在職管理者"),
    SHIFT_CORRECTION_INVALID(HttpStatus.BAD_REQUEST, "出勤更正內容不合法"),
    SHIFT_CORRECTION_TOO_OLD(HttpStatus.BAD_REQUEST, "超過可更正的期限"),
    SHIFT_CORRECTION_SELF(HttpStatus.FORBIDDEN, "不可更正自己的出勤"),

    // 菜單
    MENU_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到菜單分類"),
    MENU_CATEGORY_IN_USE(HttpStatus.CONFLICT, "分類仍有品項，無法執行此操作"),
    MENU_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到菜單品項"),
    MENU_ITEM_NOT_ARCHIVED(HttpStatus.CONFLICT, "品項必須先下架才可刪除"),
    MENU_ITEM_NOT_SELLABLE(HttpStatus.CONFLICT, "品項目前不可販售"),
    MODIFIER_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到選項群組"),
    MODIFIER_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到選項"),
    INVALID_PRICE(HttpStatus.BAD_REQUEST, "價格不合法"),
    INVALID_IMAGE(HttpStatus.BAD_REQUEST, "圖片不合法"),
    INVALID_SORT_REQUEST(HttpStatus.BAD_REQUEST, "排序內容不合法"),

    // 桌位與用餐
    TABLE_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到桌位"),
    TABLE_NO_DUPLICATE(HttpStatus.CONFLICT, "桌號重複"),
    TABLE_OCCUPIED(HttpStatus.CONFLICT, "桌位使用中"),
    TABLE_UNAVAILABLE(HttpStatus.CONFLICT, "桌位目前不可使用"),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到用餐紀錄"),
    SESSION_NOT_OPEN(HttpStatus.CONFLICT, "用餐紀錄不在使用中"),
    SESSION_HAS_SUBMITTED_ORDERS(HttpStatus.CONFLICT, "用餐紀錄已有送出的訂單"),
    SESSION_PENDING_RECHECKOUT(HttpStatus.CONFLICT, "用餐紀錄待重新結帳，無法執行此操作"),
    QR_EXPIRED(HttpStatus.CONFLICT, "QR 已過期"),
    SESSION_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "連結無效"),
    CUSTOMER_ORDER_LIMIT(HttpStatus.CONFLICT, "已達顧客送單數量上限"),
    CUSTOMER_ORDER_TOO_FAST(HttpStatus.TOO_MANY_REQUESTS, "送單太頻繁，請稍後再試"),

    // 訂單
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "數量不合法"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到訂單"),
    ORDER_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到訂單品項"),
    ORDER_ITEM_CANCELLED(HttpStatus.CONFLICT, "訂單品項已取消"),
    ORDER_ITEM_ALREADY_READY(HttpStatus.CONFLICT, "訂單品項已完成"),
    INVALID_ORDER_ITEM_TRANSITION(HttpStatus.CONFLICT, "品項狀態不可這樣變更"),
    ORDER_NOT_EDITABLE(HttpStatus.CONFLICT, "訂單目前不可編輯"),
    EMPTY_DRAFT(HttpStatus.CONFLICT, "草稿是空的"),
    DRAFT_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到草稿品項"),
    INVALID_MODIFIER_SELECTION(HttpStatus.BAD_REQUEST, "選項選擇不合法"),
    MODIFIER_GROUP_SELECTION_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "選項群組的選擇數量超出範圍"),
    MODIFIER_DUPLICATE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "此選項群組不允許重複選擇"),

    // 結帳與付款
    CHECKOUT_IN_PROGRESS(HttpStatus.CONFLICT, "結帳進行中，無法執行此操作"),
    UNSENT_ITEMS_EXIST(HttpStatus.CONFLICT, "仍有尚未送出的品項"),
    NOTHING_TO_CHECKOUT(HttpStatus.CONFLICT, "沒有可結帳的品項"),
    CHECKOUT_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到帳單"),
    CHECKOUT_NOT_IN_PROGRESS(HttpStatus.CONFLICT, "帳單不在結帳中"),
    CHECKOUT_NOT_VOIDABLE(HttpStatus.CONFLICT, "帳單不可作廢"),
    CHECKOUT_VOID_NOT_SAME_DAY(HttpStatus.CONFLICT, "只能作廢今天營業日的帳單"),
    CHECKOUT_HAS_PAYMENTS(HttpStatus.CONFLICT, "帳單已有付款，請先退回"),
    PAYMENT_METHOD_DISABLED(HttpStatus.CONFLICT, "付款方式未啟用"),
    PAYMENT_AMOUNT_INVALID(HttpStatus.BAD_REQUEST, "付款金額不合法"),
    PAYMENT_FAILED(HttpStatus.CONFLICT, "付款失敗"),
    PAYMENT_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "付款鍵已被不同內容使用"),
    PAYMENT_NOT_REFUNDABLE(HttpStatus.CONFLICT, "此付款不可退回"),
    VOID_FAILED(HttpStatus.CONFLICT, "作廢失敗"),

    // 廚房、報表、列印
    KDS_STATION_FORBIDDEN(HttpStatus.FORBIDDEN, "品項不屬於此站別"),
    KDS_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到廚房品項"),
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "日期範圍不合法"),
    DAY_CLOSE_FUTURE_DATE(HttpStatus.BAD_REQUEST, "不可日結未來日期"),
    PRINT_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到列印工作");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
