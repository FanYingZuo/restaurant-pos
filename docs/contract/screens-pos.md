# POS 平板畫面清單（卡 1.1）

> **狀態：已凍結（卡 1.7，使用者已確認）。**
> 規格來源：`docs/plan/01-PLAN.md` S13、§4.5；API 欄位以 `docs/contract/openapi.yaml`（由 `fragments/*.yaml` 合併）為準，括號內為 operationId。
> 通則：所有請求自動帶 `X-Device-Token`；管控頁面另帶 `X-Unlock-Token`；錯誤一律以**錯誤碼**判斷並顯示繁體中文提示；`CONCURRENT_MODIFICATION` 一律提示「資料已被他人更新，請重新操作」並重新載入。

## 共用元件

| 元件 | 行為 | API |
| --- | --- | --- |
| PIN 輸入 | 4～6 位數字鍵盤；不顯示明碼、不寫入任何儲存。錯誤：`INVALID_PIN`（提示 PIN 錯誤）、`PIN_LOCKED`（本裝置暫時無法輸入 PIN，約 5 分鐘） | 依使用情境 |
| 管理者解鎖 | 進入「管控頁面」前輸入管理者 PIN 與功能（`EMPLOYEE_MANAGE`／`TABLE_LAYOUT`／`DEVICE_MANAGE`）；取得 `unlockToken` 存記憶體（不寫入儲存）；**離開頁面即釋放**；任一請求回 `FORBIDDEN` → 視為已失效，回到解鎖畫面 | `unlock` POST /api/staff/unlock；`releaseUnlock` DELETE /api/staff/unlock |
| 冪等鍵 | 送出草稿的 `requestId`、付款的 `paymentKey` 由前端產生（1～64 字，英數字／底線／連字號）；**重送時沿用同一個** | — |
| 錯誤對照 | 錯誤碼 → 繁體中文提示（S12 共用 API 用戶端） | 全部 |
| 裝置憑證失效 | `DEVICE_TOKEN_INVALID`（401）→ 清除本機憑證並回裝置配對頁 | — |

---

## P1 打卡

**目的**：員工輸入 PIN 上下班，查詢狀態。
**畫面**：PIN 鍵盤；輸入後顯示員工姓名、上班／下班兩個大按鈕（**亮的是下一步該按的**，灰色仍可按）、今日班次清單、累計分鐘、警告。

| 操作 | API | 送出 | 使用的回應欄位 |
| --- | --- | --- | --- |
| 輸入 PIN 後查詢（不打卡） | `getClockStatus` POST /api/staff/clock-status | `pin` | `employeeName`、`state`、`nextAction`（決定哪個按鈕亮）、`warnings`、`todayShifts[]`（`clockInAt`、`clockOutAt`、`minutes`、`state`、`corrected`）、`todayTotalMinutes` |
| 按「上班」 | `clockIn` POST /api/staff/clock-in | `pin` | 同上 |
| 按「下班」 | `clockOut` POST /api/staff/clock-out | `pin` | 同上 |

**錯誤提示**：`ALREADY_CLOCKED_IN`（已在上班中）、`CLOCK_TOO_FAST`（請稍候再打卡）、`PIN_LOCKED`、`INVALID_PIN`。
**警告顯示**：`MISSING_CLOCK_IN`（沒上班就下班：本次不計工時）、`MISSING_CLOCK_OUT`（前一營業日忘記下班：該班不計工時）。

## P2 桌位看板

**目的**：看全店桌況，進入開桌或用餐詳情。
**畫面**：桌位版面（方／圓／長方三種形狀、兩指縮放、依座標與寬高擺放）；空桌／使用中；使用中的桌顯示帳單號與小計；底部「待重新結帳」清單入口。

| 操作 | API | 使用的回應欄位 |
| --- | --- | --- |
| 載入版面與桌況 | `listTables` GET /api/pos/tables | `id`、`tableNo`、`shape`、`width`、`height`、`x`、`y`、`area`、`inUse`、`session`（`id`、`billNo`、`status`、`guestCount`、`checkoutInProgress`、`pendingRecheckout`） |
| 使用中桌的小計 | `getSessionDetail` GET /api/pos/sessions/{sessionId} | `subtotal`（每個使用中桌各呼叫一次，不新增看板摘要 API） |
| 待重新結帳清單 | `listPendingRecheckout` GET /api/pos/sessions/pending-recheckout | `id`、`tableNo`、`billNo`、`guestCount` |

**互動**：點空桌 → P3 開桌；點使用中桌 → P5 用餐詳情；點待重新結帳項目 → P5（只能做結帳相關操作）。
**更新方式**：進入畫面與每次操作後重新載入（POS 不訂閱推播；若日後要即時更新，用 `/ws` 的 `BOARD_RELOAD` 觸發重新載入）。

## P3 開桌＋顯示 QR

| 步驟 | API | 送出／使用 |
| --- | --- | --- |
| 輸入人數（1～99）後開桌 | `openTable` POST /api/pos/tables/{tableId}/open | `guestCount` → 回 `SessionSummary`（**不含 token**） |
| 顯示 QR 圖片讓顧客掃描 | `getSessionQr` GET /api/pos/sessions/{sessionId}/qr | PNG；**需帶裝置憑證標頭，前端以 blob 載入再顯示** |

**錯誤提示**：`TABLE_OCCUPIED`（此桌已被開啟，重新整理看板）、`TABLE_NOT_FOUND`。
**QR 失效顯示**：以 `qrExpiresAt`、`qrExpired` 提示「QR 已過期，顧客仍可查看但不能送新單」。QR 圖片可在 P5 再次顯示。

## P4 點餐（草稿）

**目的**：服務員為某用餐紀錄點餐並送出。
**畫面**：左側分類與品項（`sellable=false` 的品項灰階不可點）；選項選擇對話框（依群組顯示「至少 N、最多 M」，群組若 `allowDuplicate` 可加數量）；右側草稿清單（可修改、刪除）；送出按鈕。

| 操作 | API | 送出 | 使用的回應欄位 |
| --- | --- | --- | --- |
| 載入菜單 | `getMenuTree` GET /api/pos/menu | — | 分類（`name`）→ 品項（`id`、`name`、`price`、`sellable`、`soldOut`、`imageKey`）→ 群組（`minSelect`、`maxSelect`、`allowDuplicate`）→ 選項（`id`、`name`、`priceDelta`）；圖片以 `getMenuImage` 載入 |
| 載入草稿 | `getSessionDetail` | — | `draft.items[]` |
| 加入品項 | `addDraftItem` POST …/draft/items | `menuItemId`、`quantity`（1～99）、`modifiers[{modifierId,quantity}]`、`note`（≤255） | 整張草稿 `items[]`（`unitPrice`、`lineTotal`…；價格以伺服器為準） |
| 修改草稿品項 | `replaceDraftItem` PUT …/draft/items/{draftItemId} | 同上（整筆取代） | 同上 |
| 刪除草稿品項 | `deleteDraftItem` DELETE …/draft/items/{draftItemId} | — | 同上 |
| 送出 | `submitDraft` POST …/draft/submit | `requestId`（前端產生，重送沿用）、`note`（訂單備註） | `batchNo`、`itemCount`、`replayed` |

**前端驗證（純邏輯，Vitest）**：數量 1～99；群組選取數在最少與最多之間；不允許重複的群組數量不大於 1（與伺服器同規則，伺服器仍會再驗）。
**錯誤提示**：`MENU_ITEM_NOT_SELLABLE`（該品項已售完或下架，請移除）、`EMPTY_DRAFT`、`CHECKOUT_IN_PROGRESS`（結帳中不能點餐）、`SESSION_PENDING_RECHECKOUT`、`SESSION_NOT_OPEN`、`INVALID_MODIFIER_SELECTION`、`MODIFIER_GROUP_SELECTION_OUT_OF_RANGE`、`MODIFIER_DUPLICATE_NOT_ALLOWED`、`INVALID_QUANTITY`。**送出失敗不清空草稿**。

## P5 用餐詳情

**目的**：查看各批訂單與品項狀態；修改、取消、改人數、服務費。
**畫面**：頂部用餐資訊（桌號、帳單號、人數、服務費開關、QR 到期）；草稿區；各批訂單（批次號、來源、狀態、備註、品項與狀態標籤）；小計（**不含草稿與服務費**）；操作按鈕。

| 操作 | API | 送出 | 備註 |
| --- | --- | --- | --- |
| 載入 | `getSessionDetail` | — | `session`、`draft`、`orders[]`（`batchNo`、`status`、`source`、`note`、`version`、`items[]`）、`subtotal` |
| 修改已送出品項 | `modifyOrderItem` PATCH /api/pos/order-items/{orderItemId} | `expectedVersion`（取自品項 `version`）、`quantity`、`modifiers`、`note` | 已完成／已取消不可改：`ORDER_ITEM_ALREADY_READY`、`ORDER_ITEM_CANCELLED` |
| 修改訂單備註 | `updateOrderNote` PATCH /api/pos/orders/{orderId}/note | `expectedVersion`、`note` | |
| 取消品項（PIN） | `cancelOrderItem` POST …/cancel | `pin`、`reason` | |
| 取消整單（PIN） | `cancelOrder` POST /api/pos/orders/{orderId}/cancel | `pin`、`reason` | 有已完成品項 → `ORDER_ITEM_ALREADY_READY` |
| 取消開桌（PIN） | `cancelSession` POST …/cancel | `pin`、`reason` | 有未取消正式單品項 → `SESSION_HAS_SUBMITTED_ORDERS` |
| 修改人數 | `updateGuestCount` PATCH …/guest-count | `guestCount` | |
| 服務費開關（PIN） | `setServiceCharge` PUT …/service-charge | `enabled`、`pin` | |
| 再看 QR | `getSessionQr` | — | 同 P3 |
| 進入結帳 | → P6 | — | |

**狀態標籤**：`PENDING` 待處理、`IN_PROGRESS` 製作中、`DONE` 已完成、`CANCELLED` 已取消；訂單 `SUBMITTED`／`IN_PROGRESS`／`PARTIALLY_SERVED`／`SERVED`／`CANCELLED`。
**限制顯示**：`checkoutInProgress` 或 `pendingRecheckout` 為真時，點餐、修改、取消、人數、服務費操作一律停用並說明原因。

## P6 結帳

**目的**：開始結帳、收款、退回、放棄、作廢、收據、結束不收費。

| 操作 | API | 送出 | 使用的回應欄位 |
| --- | --- | --- | --- |
| 開始結帳 | `startCheckout` POST /api/pos/sessions/{sessionId}/checkout | — | 帳單：`lines[]`（含未完成品項，以 `itemStatus` 標示）、`subtotal`、`charges[]`（服務費與費率）、`discount`、`total`、`paid`、`change`、`unpaid`、`quickAmounts[]`、`payments[]` |
| 付款方式 | `listPaymentMethods` GET /api/pos/payment-methods | — | `code`、`name`、`enabled` |
| 付款（可部分） | `pay` POST /api/pos/checkouts/{checkoutId}/payments | `paymentKey`（重送沿用）、`methodCode`、`receivedAmount`（整數 >0） | 回目前帳單；**`PAYMENT_FAILED` 時資料已提交，須重新載入帳單** |
| 退回付款（PIN） | `refundPayment` POST …/payments/{paymentId}/refund | `pin`、`reason` | 只限結帳中、成功的付款 |
| 放棄結帳（二次確認） | `abandonCheckout` POST …/abandon | — | 有成功付款 → `CHECKOUT_HAS_PAYMENTS`（先退回） |
| 作廢（PIN，二次確認） | `voidCheckout` POST …/void | `pin`、`reason` | 只限已完成且今天營業日；`CHECKOUT_VOID_NOT_SAME_DAY` |
| 收據預覽 | `getReceiptPreview` GET …/receipt | — | `text`（純文字）與結構化欄位；未完成時付款區塊為 null |
| 結束不收費（PIN） | `closeWithoutPayment` POST /api/pos/sessions/{sessionId}/close-without-payment | `pin`、`reason` | 僅待重新結帳的用餐紀錄 |

**畫面要點**：快速金額按鈕直接帶入 `receivedAmount`；付足額後帳單 `status=COMPLETED`，桌位回空桌，顯示收據預覽；`unpaid` 為 0 時 `quickAmounts` 為空。
**錯誤提示**：`UNSENT_ITEMS_EXIST`（草稿尚有品項，請先送出或清空）、`NOTHING_TO_CHECKOUT`、`PAYMENT_METHOD_DISABLED`、`PAYMENT_AMOUNT_INVALID`、`PAYMENT_IDEMPOTENCY_CONFLICT`（請重新操作，產生新的付款鍵）、`CHECKOUT_NOT_IN_PROGRESS`、`PAYMENT_NOT_REFUNDABLE`、`VOID_FAILED`。

## P7 報表與日結

| 區塊 | API | 輸入 | 使用的回應欄位 |
| --- | --- | --- | --- |
| 今日總覽 | `getTodayOverview` GET /api/pos/reports/today | — | `revenue`、`openTableCount`、`onDutyCount` |
| 營業額報表 | `getRevenueReport` GET …/revenue | `from`、`to`（營業日，含；最多 92 日） | `days[]`（`businessDate`、`tableCount`、`guestCount`、`revenue`、`subtotal`、`serviceCharge`、`voidCount`、`abandonCount`、`refundCount`、`cancelledItemCount`）、`total` |
| 日結預覽（免 PIN） | `previewDayClose` GET …/day-close/preview | `date`、`reserveFund`、`countedCash` | `cashReceived`、`expectedCash`、`difference` |
| 日結存檔（PIN） | `saveDayClose` POST …/day-close | `date`、`reserveFund`、`countedCash`、`pin` | `id`、`closedAt`、`employeeName` |
| 工時報表 | `getWorkHoursReport` GET …/work-hours | `from`、`to`、`groupBy`（日／週／月）、`employeeId` | `groups[].rows[]`（`employeeName`、`minutes`、`shiftCount`、`includesCorrected`）、`anomalies[]`（上班未打卡、下班未打卡） |

**錯誤提示**：`INVALID_DATE_RANGE`、`DAY_CLOSE_FUTURE_DATE`。

## P8 管理功能

進入各管理頁前先走「管理者解鎖」；離開即釋放。

### P8-1 員工管理（`EMPLOYEE_MANAGE`）

| 操作 | API | 備註 |
| --- | --- | --- |
| 列表 | `listEmployees` GET /api/staff/employees | 不含 PIN；含已停用 |
| 新增 | `createEmployee` POST | `name`、`role`（`STAFF`／`MANAGER`）、`pin`；`PIN_ALREADY_USED` |
| 改名／角色 | `updateEmployee` PATCH …/{employeeId} | `LAST_MANAGER` |
| 換 PIN | `changeEmployeePin` PUT …/{employeeId}/pin | |
| 停用 | `deactivateEmployee` POST …/{employeeId}/deactivate | `LAST_MANAGER` |

### P8-2 出勤更正（`EMPLOYEE_MANAGE`）

| 操作 | API | 備註 |
| --- | --- | --- |
| 選班次 | `getClockHistory` GET /api/staff/clock-history | 取得 `shiftId`、`state`、`corrected`（需管理者解鎖；打卡歷史不開放給一般服務員） |
| 補／修時間 | `correctShift` PATCH /api/staff/shifts/{shiftId} | `clockInAt`、`clockOutAt`、`reason`（必填） |
| 新增完整班次 | `createShift` POST /api/staff/shifts | `employeeId`、`clockInAt`、`clockOutAt`、`reason` |

錯誤：`SHIFT_CORRECTION_INVALID`、`SHIFT_CORRECTION_TOO_OLD`（限 31 天內）、`SHIFT_CORRECTION_SELF`。

### P8-3 稽核紀錄（`EMPLOYEE_MANAGE`）

`listAuditLogs` GET /api/staff/audit-logs：篩選 `from`、`to`、`action`；分頁 `page`、`size`；欄位 `occurredAt`、`action`、`targetType`、`targetId`、`beforeValue`、`afterValue`、`employeeId`、`deviceId`、`reason`。

### P8-4 桌位版面編輯（`TABLE_LAYOUT`）

| 操作 | API | 備註 |
| --- | --- | --- |
| 載入 | `listTables` | 同 P2 |
| 新增 | `createTable` POST /api/pos/tables | `tableNo`（1～20 字）、`capacity`、`shape`、`width`、`height`、`x`、`y`、`area`；`TABLE_NO_DUPLICATE` |
| 拖曳座標／縮放 | `updateTable` PATCH /api/pos/tables/{tableId} | 方形與圓形高＝寬；使用中不可改（`TABLE_OCCUPIED`） |
| 刪除 | `deleteTable` DELETE | 使用中不可刪 |

### P8-5 菜單管理（裝置憑證即可，免 PIN、免解鎖）

| 操作 | API |
| --- | --- |
| 載入 | `getMenuTree` |
| 分類新增／修改（含上下架）／刪除／排序 | `createCategory`、`updateCategory`、`deleteCategory`、`reorderCategories` |
| 品項新增／修改（含改價、上下架）／刪除／排序 | `createItem`、`updateItem`、`deleteItem`、`reorderItems` |
| 售完／恢復 | `markItemSoldOut`、`clearItemSoldOut` |
| 選項群組 | `createModifierGroup`、`updateModifierGroup`、`deleteModifierGroup`、`reorderModifierGroups` |
| 選項 | `createModifier`、`updateModifier`、`deleteModifier`、`reorderModifiers` |
| 圖片 | `uploadItemImage`（multipart `file`，≤2 MB，JPEG／PNG）、`deleteItemImage` |

錯誤：`INVALID_PRICE`、`MENU_CATEGORY_IN_USE`、`MENU_ITEM_NOT_ARCHIVED`（先下架才可刪）、`INVALID_SORT_REQUEST`（排序清單必須剛好等於該層全部）、`INVALID_IMAGE`。

### P8-6 裝置管理（`DEVICE_MANAGE`）

| 操作 | API | 備註 |
| --- | --- | --- |
| 列表 | `listDevices` GET /api/staff/devices | 不含憑證 |
| 建立並顯示配對碼 | `createDevice` POST | `name`、`deviceType`（`POS`／`KDS`）；配對碼**只顯示一次**，附 `pairingExpiresAt` 倒數 |
| 重新產生配對碼 | `regeneratePairingCode` POST …/{deviceId}/pairing-code | |
| 撤銷 | `revokeDevice` POST …/{deviceId}/revoke | 二次確認；`DEVICE_SELF_REVOKE` |

## P9 列印問題與重印（S16，第三部分最後才做）

| 操作 | API | 備註 |
| --- | --- | --- |
| 問題清單 | `listPrintProblems` GET /api/pos/print/problems | 失敗與等待超過 30 秒；**不含內容**；顯示 `type`、`target`、`status`、`attempts`、`lastError`、`createdAt` |
| 重印單據 | `reprintJob` POST /api/pos/print/jobs/{jobId}/reprint | 只能重印問題清單內的工作（不新增依用餐紀錄列出工作的 API） |
| 重印 QR | `reprintQr` POST /api/pos/sessions/{sessionId}/reprint-qr | 不產生新 token |
| 重印收據 | `reprintReceipt` POST /api/pos/checkouts/{checkoutId}/reprint-receipt | |
