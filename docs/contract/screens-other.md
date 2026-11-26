# KDS、顧客手機、裝置配對頁畫面清單（卡 1.2）

> **狀態：已凍結（卡 1.7，使用者已確認）。**
> 規格來源：`docs/plan/01-PLAN.md` S6、S8、S12、S14、S15、§4.5；API 欄位以 `docs/contract/openapi.yaml` 為準，括號內為 operationId。
> 三個前端分開路徑、與 API 同來源（D-31）：`/pos/`、`/kds/`、`/customer/`；裝置配對頁為各前端自己的路由：`/pos/pair`、`/kds/pair`。

---

## D1 裝置配對頁（POS、KDS 共用）

**目的**：新裝置以配對碼取得憑證。
**畫面**：配對碼輸入（8 位英數字）、送出按鈕、錯誤提示。

| 操作 | API | 送出 | 使用的回應欄位 |
| --- | --- | --- | --- |
| 配對 | `pairDevice` POST /api/pairing（**無需裝置憑證**） | `pairingCode` | `deviceToken`（**只出現這一次**，存入裝置瀏覽器的本機儲存；不顯示、不寫日誌）、`deviceType`（決定導向 `/pos/` 或 `/kds/`）、`name`、`deviceId` |

**錯誤提示**：`PAIRING_CODE_INVALID`（配對碼錯誤、已過期或已使用）、`PAIRING_LOCKED`（嘗試次數過多，請 10 分鐘後再試）。
**憑證失效**：任何 API 回 `DEVICE_TOKEN_INVALID`（401）→ 清除本機憑證 → 回本頁。WebSocket 被斷線且重連 CONNECT 失敗時亦同。
**首次啟動**：首台 POS 的配對碼印在主控台（開發環境），由使用者手動輸入。

---

## K1 廚房（KDS）清單

**目的**：廚房看自己站別（K1 或 K2）的待處理與製作中品項，更新製作狀態。
**畫面**：站別切換（K1／K2；平板開啟時選擇並記住）；品項卡片依送出順序（`orderItemId` 遞增）排列；卡片顯示桌號、批次號、品項名稱與數量、選項（名稱×數量）、品項備註、訂單備註、送出時間、狀態；按鈕「開始製作」（待處理時）、「完成」。
**狀態標示**：`PENDING` 待處理、`IN_PROGRESS` 製作中。

| 操作 | API | 送出 | 備註 |
| --- | --- | --- | --- |
| 載入清單 | `listStationItems` GET /api/kds/stations/{station}/items | — | 回 `items[]`：`orderItemId`、`orderId`、`sessionId`、`tableNo`、`batchNo`、`name`、`quantity`、`note`、`modifiers[]`、`status`、`version`、`submittedAt`、`orderNote`；`generatedAt` |
| 開始製作 | `startItem` POST …/items/{orderItemId}/start | `expectedVersion`（取自卡片 `version`） | 成功後以回應 `status`、`version` 更新卡片 |
| 完成 | `completeItem` POST …/items/{orderItemId}/complete | `expectedVersion` | 成功後從清單移除 |

**錯誤提示**：`CONCURRENT_MODIFICATION`（品項已被更新，重新載入）、`ORDER_ITEM_CANCELLED`（此品項已取消，從清單移除並提示）、`INVALID_ORDER_ITEM_TRANSITION`、`SESSION_NOT_OPEN`、`KDS_ITEM_NOT_FOUND`、`KDS_STATION_FORBIDDEN`。
**先付後吃**：用餐紀錄已結束但今天營業日內的未完成品項仍在清單上，照常可操作。

## K2 即時更新（WebSocket，卡 14.2）

| 項目 | 內容 |
| --- | --- |
| 連線 | STOMP over 原生 WebSocket，端點 `/ws`；CONNECT 帧帶 `X-Device-Token`；失敗被斷線 → 視同憑證失效 |
| 訂閱 | `/topic/kds/K1` 或 `/topic/kds/K2`（只訂閱，不傳訊息） |
| 訊息 | `ITEM_UPSERT`（`item` 完整資料）、`ITEM_REMOVE`（`orderItemId`）、`BOARD_RELOAD` |
| 去重 | 以「`orderItemId` ＋ `version`」；只套用版本 ≥ 目前版本的更新；**移除一律套用** |
| 斷線 | 重連成功後**整份重新載入**（`listStationItems`）；60 秒沒收到任何訊息視為斷線 |
| 對帳 | 每 60 秒定時整份重新載入 |
| 心跳 | 伺服器每 25 秒送心跳；裝置被撤銷後下次心跳斷線 |

---

## C1 顧客手機點餐（路徑 `/customer/`）

**入口**：顧客掃桌上 QR → 網址 `…/customer/order/{sessionId}#t={token}`。
**token 處理（必做）**：頁面載入後立即讀出 `#t=` 片段、**用 `history.replaceState` 清掉網址列**，之後只放在記憶體並以 `X-Session-Token` 標頭送出；不寫入 localStorage、不寫日誌、不出現在任何錯誤訊息。

### C1-1 驗證與狀態

| 操作 | API | 使用的回應欄位 |
| --- | --- | --- |
| 驗證用餐紀錄 | `verifyCustomerSession` GET /api/customer/sessions/{sessionId} | `status`、`tableNo`、`qrExpiresAt`、`expired`、`canOrder`、`serviceChargeEnabled` |

- `SESSION_TOKEN_INVALID`（401，三種原因回應完全相同）→ 顯示「連結無效，請洽服務人員」。
- 依狀態顯示提示：`expired`（QR 已過期，可查看不能點餐）、`status=CLOSED`（用餐已結束）、`canOrder=false` 但未過期（結帳中或待重新結帳）。

### C1-2 菜單瀏覽、購物車、選項

| 操作 | API | 使用的回應欄位 |
| --- | --- | --- |
| 載入菜單（無需驗證） | `getCustomerMenu` GET /api/customer/menu | 分類 `name` → 品項（`id`、`name`、`price`、`imageKey`）→ 群組（`minSelect`、`maxSelect`、`allowDuplicate`、`modifiers[]`） |
| 載入圖片（無需驗證） | `getMenuImage` GET /api/customer/images/{imageKey} | JPEG／PNG；可直接用圖片標籤 |

- **購物車只存在自己的手機**（未送出前不寫伺服器）；多台手機可共用同桌訂單（送出後才進伺服器）。
- **前端驗證（純邏輯，Vitest）**：數量 1～99；群組選取數在最少與最多之間；不允許重複的群組數量不大於 1；單張最多 30 項（超過時禁止送出）。
- 顧客端不顯示服務費金額與小計（價格僅供選購參考；服務費開關 `serviceChargeEnabled` 以文字提示「另收服務費」）。

### C1-3 送單

| 操作 | API | 送出 | 使用的回應欄位 |
| --- | --- | --- | --- |
| 送單 | `submitCustomerOrder` POST …/orders | `requestId`（前端產生，**重送沿用同一個**）、`items[{menuItemId,quantity,modifiers,note}]`、`note` | `batchNo`、`itemCount`、`replayed` |

**錯誤提示**：

| 錯誤碼 | 提示 |
| --- | --- |
| `QR_EXPIRED` | QR 已過期，請洽服務人員 |
| `CHECKOUT_IN_PROGRESS` | 正在結帳，無法再點餐 |
| `SESSION_NOT_OPEN` | 用餐已結束 |
| `SESSION_PENDING_RECHECKOUT` | 本桌目前無法點餐，請洽服務人員 |
| `CUSTOMER_ORDER_TOO_FAST`（429） | 送單太頻繁，請稍候再試 |
| `CUSTOMER_ORDER_LIMIT` | 已達單次或本桌訂單數量上限，請洽服務人員 |
| `MENU_ITEM_NOT_SELLABLE` | 部分品項已售完，請調整後再送 |
| `DUPLICATE_REQUEST` | 請求編號重複但內容不同，產生新的請求編號後重試 |

- **網路逾時不確定是否成功時**：用同一個 `requestId` 與同樣內容重送；伺服器回原結果（200，`replayed=true`），不會重複出單。

### C1-4 查看本桌訂單

| 操作 | API | 使用的回應欄位 |
| --- | --- | --- |
| 載入 | `listCustomerOrders` GET …/orders | `orders[]`：`batchNo`、`submittedAt`、`note`、`items[]`（`name`、`quantity`、`modifiers[]`、`note`、`status`） |

- 含服務員送的單；已取消品項標示「已取消」；**不含內部編號、版本、取消原因、價格**。
- 過期後仍可查看。更新方式：進入頁面與每次送單後重新載入，並提供手動重新整理（顧客端不使用 WebSocket；WebSocket 需裝置憑證）。
