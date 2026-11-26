# 對話紀錄與待決問題

> 本檔是歷史，不是規格；規格以 `01-PLAN.md`（規格說明書 v1）為準。
> 規則：每次對話結束加一筆，**最新的在最上面**；只記結論，不貼程式碼或對話原文；不得出現任何機密值（見 `03-BOUNDARIES.md` 第 3 節）。

## 目前狀態

- 規格：**規格說明書 v1（2026-11-26）**，四份文件 01-PLAN／02-TODO／03-BOUNDARIES／04-LOG。
- 實作：**S0～S6 完成。57/107 張卡。全部測試 607/607。**
- 待決：無。
  - 已結案：#1（HTTP 狀態對照）、#2（列印重試等待）、#3（建置工具）、#4（套件根名稱 `restaurant_pos`）。
- 開工前（使用者）：git commit 留起點 → 四份文件放進 `docs/plan/`、舊文件移 `docs/archive/` → 建立 `CLAUDE.md` → 確認 Docker 可執行、JDK 17 可用。
- 下一張卡：**7.1**（S7 結帳與付款）。

---

## 格式

```
## YYYY-MM-DD　卡 X.Y 標題（或：討論主題）
- 做了：一兩句
- 動到的程式碼：路徑（新增／修改／刪除），沒動程式寫「無（文件卡）」
- 決定：寫到哪（01-PLAN §x／02-TODO）
- 待決：#N（沒有寫「無」）
- 測試：本卡 N／N、全部 N／N（文件卡省略）
- 下一步：
```

---

## 2026-11-26　S6 廚房 KDS 完成（卡 6.1～6.4，S6 驗收通過）
- 做了：站別清單查詢（固定次數查詢、批次取桌號）、KDS REST（清單／開始／完成，直接呼叫 5.8）、WebSocket（STOMP，端點 /ws，CONNECT 帶 X-Device-Token 驗證、只能訂閱 /topic/kds/K1、K2、傳入訊息忽略、只允許基底網址來源、心跳、撤銷後下次心跳斷線）、推播監聽器（提交後、重新讀取、讀不到改送移除、推播失敗只記日誌）。
- 動到的程式碼：新增 kds 模組；pom 加 spring-boot-starter-websocket（規格 §3 清單內）；DeviceAuthenticator 加 isActive；order 加 KitchenQueryService；table 加批次取桌號。測試用 RecordingPusher 改為記錄後轉給真推播器。
- 決定（規格沒寫、AI 草擬）：
  - 訊息型別 ITEM_UPSERT／ITEM_REMOVE／BOARD_RELOAD；移除不帶站別，所以兩站都送。
  - 伺服器心跳預設 25 秒（restaurantpos.ws.heartbeat-ms 可調，測試用 1 秒）；裝置有效性每個心跳週期重新檢查。
  - 清單查詢上限測試放寬為「與品項數無關且 ≤10 條 SQL」（含裝置驗證）。
- 待決：無。
- 測試：全部 607／607。
- 下一步：S7 結帳與付款。

---

## 2026-11-26　S5 訂單 完成（卡 5.1～5.11，S5 驗收通過）
- 做了：訂單狀態與品項狀態純函式、選項驗證與快照、草稿（新增／修改／刪除）、送出（冪等重播、批次號、重新驗證）、修改已送出品項與訂單備註、取消品項／整單／開桌（PIN）、廚房品項狀態服務、用餐詳情（小計）、廚房單／修改單／取消單文字格式、migration V7（orders、order_item、order_item_modifier、order_history）。
- 動到的程式碼：新增 order 模組；shared 的 PinVerifier.isValidFormat、SessionView.requireEditable；TableSessionService 服務費先檢查 PIN 格式。
- 決定（規格沒寫、AI 草擬）：
  - 單據文字格式為 AI 草擬（標題「桌號　第 N 批　時間」、「2 x 牛肉麵」、縮排「+ 加麵 x1」、「備註：」、標記【修改後】／【取消】、「取消原因：」）。
  - 取消品項與取消整單不被「待重新結帳」擋住（合約清單如此）；修改、備註、取消開桌會擋。
  - 取消開桌寫稽核 SESSION_CANCELLED。
  - 訂單版本在任何品項或備註變動時遞增；草稿送出本身不遞增（對外第一次看到的版本為 0）。
  - 送出重播回傳原結果，itemCount 為該張訂單的品項數；草稿送出時以最新菜單重新快照。
  - 廚房狀態檢查順序：品項存在 → 站別 → 用餐紀錄未取消（已結束可，不看結帳旗標）→ 品項未取消 → 版本 → 轉換。
- 待決：無。
- 測試：全部 569／569。
- 下一步：S6 廚房 KDS。

---

## 2026-11-26　S4 桌位與用餐紀錄 完成（卡 4.1～4.5，S4 驗收通過）
- 做了：桌位 CRUD 與桌號自然排序、開桌（帳單號流水、token、QR 到期）、QR 圖片（ZXing）、修改人數與服務費（PIN＋稽核）、生命週期方法（結束、取消、兩種重開）與待重新結帳清單、S4 驗收。全部測試 418／418 通過。
- 動到的程式碼：新增 table 模組（DiningTable、DailySequence、TableSession、Repository、TableLayoutService、TableSessionService、QrImageService、DailySequenceService、兩個 Controller、SessionView、兩個事件）；遷移 V6；pom 新增 zxing core、javase 3.5.3（規格 §3 明列）。
- 決定（規格沒寫、AI 草擬）：
  - 「目前占用桌位唯一」以 occupying_table_id（只在使用中且占桌時有值）的唯一索引實現；token 存明碼（重印 QR 需要），但任何 API 回應與 toString 都不含。
  - 帳單號流水列在獨立交易中先確保存在，避免每個營業日第一次同時開不同桌時其中一邊失敗；欄位名 last_no（last_value 是 MySQL 保留字）。
  - 給其他模組的生命週期方法（lockById、lockEditable、close、cancel、reopen、setCheckoutInProgress）一律要求既有交易（MANDATORY），回傳 SessionView（不含 token）。
  - 修改人數與服務費的檢查順序：使用中 → 待重新結帳 → 結帳中；服務費先檢查狀態、之後才驗 PIN；值沒變不寫稽核。
  - 整數欄位收到小數（如人數 2.5）一律拒絕（spring.jackson.deserialization.accept-float-as-int=false）。
  - 桌號唯一只做程式檢查（規格明訂），不加資料庫唯一索引。
  - 測試基底清表改用 DELETE（比 TRUNCATE 快）。
- 待決：無。
- 測試：全部 418／418。
- 下一步：S5 訂單。
## 2026-11-26　S3 菜單 完成（卡 3.1～3.8，S3 驗收通過）
- 做了：分類、品項、選項群組與選項（含稽核）、售完與恢復、可售判斷與給訂單的快照、排序、圖片上傳／刪除／取圖、顧客菜單與 S3 驗收。全部測試 346／346 通過。
- 動到的程式碼：新增 menu 模組（實體、Repository、MenuQueryService、MenuCategoryService、MenuItemService、ModifierService、MenuController、CustomerMenuController）、shared.model.Station；遷移 V5（station 種子 K1、K2，menu_category、menu_item、modifier_group、modifier）。
- 決定（規格沒寫、AI 草擬）：
  - 站別列舉放 shared（kds 不可依賴 menu）；station 表有種子但本階段不建實體。
  - 顧客菜單與菜單圖片路徑在 /api/customer 底下，但資料屬菜單模組，所以 Controller 放 menu 模組。
  - 品項新增預設下架（合約 default false）；分類已下架時仍可把品項設為上架，但該品項不可售（可售四條件之一）。
  - 排序：先鎖父層（分類排序則鎖全部未刪除分類），再用鎖到的資料比對「必須剛好相等」。
  - 圖片：舊檔在交易提交後才刪、交易回滾則刪新檔；multipart 上限放寬為 10 MB，使 2～10 MB 得 INVALID_IMAGE（>10 MB 也映射為 INVALID_IMAGE，因目前只有圖片上傳是 multipart）。
  - 稽核的價格前後值存整數字串（資料庫讀出為 150.00，已轉為 150）。
  - 新增 RequestRejectedHandler：被 Spring Security 防火牆拒絕的請求（如路徑含編碼斜線）回統一格式 VALIDATION_ERROR。
  - 所有 JSON 日期請求參數須加 @DateTimeFormat(iso = ISO.DATE)（預設是依地區的短格式）。
- 待決：無。
- 測試：全部 346／346。
- 下一步：S4 桌位與用餐紀錄。
## 2026-11-26　S2 員工、裝置與出勤 完成（卡 2.1～2.10，S2 驗收通過）
- 做了：PIN 驗證（HMAC、以裝置防暴力、鎖定寫稽核）、首位管理者、上下班打卡、打卡狀態與歷史、管理者解鎖、員工管理、裝置管理、出勤更正、稽核查詢 API、對外介面（PIN 取員工、解鎖檢查、批次取員工名稱）與 S2 驗收。全部測試 282／282 在 MySQL 8 通過。另新增 ModuleDependencyTest：掃描 import 強制執行 §4.1 模組依賴方向，之後每個階段都會被檢查。
- 動到的程式碼：新增 staff.employee、staff.shift、staff.unlock、staff.audit 套件與 staff.device 的管理類別；shared.security 的 PinVerifier／UnlockGate／UnlockFunction／FailureLimiter 等；遷移 V4（employee、work_shift、work_shift_history）。
- 決定（規格沒寫、AI 草擬）：
  - 打卡「狀態」欄位：目前上班中為 ON_DUTY，否則取最近一筆班次的狀態；從來沒有班次時為 COMPLETE、下一步為 CLOCK_IN（合約 state 為必填）。
  - 前一營業日忘記下班的警告只看「前一個營業日」的未下班班次；更早的只出現在異常清單（S9）。
  - 兩次打卡間隔以該員工最後一次上班或下班時間計；剛好滿 60 秒可打。
  - 解鎖憑證不綁定裝置（規格沒寫）；閒置到期的判斷為「現在 ≥ 最後使用時間＋30 分鐘」。
  - 出勤更正的 31 天以班次營業日比較（不早於今天營業日往前 31 天）；修改時新舊營業日都要在範圍內。
  - 停用員工不能換 PIN（回 EMPLOYEE_NOT_FOUND）；停用冪等；已停用員工的班次仍可更正。
  - 同一員工的打卡、出勤更正都先鎖員工列（再鎖班次），避免死結。
- 併發重要發現（影響所有後續階段）：資料庫為 REPEATABLE READ，交易內第一次普通查詢會建立快照；若先驗 PIN 等普通查詢才去鎖，鎖到之後讀到的仍是舊快照，導致「同時上班打卡」產生 5 個班次。解法：鎖必須是交易內的第一個動作，PIN 驗證放在交易外或鎖之後。已寫入記憶與程式註解。
- 待決：無。
- 測試：S2 本階段 + S0 全部 282／282。
- 下一步：S3 菜單。
## 2026-11-26　S0 卡 0.4～0.12 完成（S0 驗收通過）
- 做了：使用者指示「全部依照建議，做到要做前端為止」，S1 凍結後依序完成 S0 剩餘卡。執行環境與啟動檢查（0.4）、錯誤碼與全域錯誤處理（0.5）、營業日與設定讀取（0.6）、Spring Security 與安全標頭（0.7）、事件／推播器／稽核（0.8）、圖片儲存（0.9）、裝置憑證過濾器（0.10）、配對 API 與防暴力與首次啟動（0.11）、操作文件與 S0 驗收（0.12，docs/operations.md）。全部測試 134／134 在 MySQL 8 通過。
- 動到的程式碼：新增 shared（config、error、setting、time、security、audit、event、push、image）與 staff.device 套件；遷移 V2（audit_log 補前值後值理由、移除 detail）、V3（device 補最後使用時間與配對碼、credential_hash 改可空）；測試基底 restaurant_pos.support；docs/operations.md。
- 決定（規格沒寫、AI 草擬）：
  - 新增依賴：spring-boot-starter-web、spring-boot-starter-security（皆為 §3 技術選型必要框架）、springdoc-openapi-starter-webmvc-ui 2.8.13（Boot 不管理版本，指定）。
  - 環境變數名稱：POS_ENV、POS_DB_URL／USER／PASSWORD、POS_BASE_URL、POS_TRUSTED_PROXIES、POS_PIN_SECRET、POS_FIRST_MANAGER_PIN、POS_IMAGE_DIR、POS_LOG_DIR（規格只列「項目」未列名稱）。「日誌檔」改為「日誌目錄」。
  - HMAC 用途前綴：PIN 用 pin:、裝置憑證用 device:（規格明訂）、配對碼用 pairing:；配對碼字元集去除 0／O／1／I。
  - print.log 保存 90 天（規格只對應用程式日誌訂 90 天）。
  - 首次啟動略為放寬：「沒有任何可用（已配對且未撤銷）裝置」時建立或重新產生首台 POS 配對碼（規格寫「沒有任何裝置」），避免操作員錯過配對碼後無法進入系統。
  - 模組依賴：shared 不能依賴 staff，所以裝置驗證用 shared 的 DeviceAuthenticator 介面、staff 實作；PIN 驗證與解鎖檢查同理（table、menu 只依賴 shared）。
  - 防暴力：達上限的那一次失敗仍回一般錯誤，之後的嘗試（含正確的）才回被鎖。
  - 非 /api、錯誤頁、/ws 的路徑一律回 404 NOT_FOUND（不是 403），不洩漏存在性。
- 待決：無。
- 測試：S0 全部 134／134（含舊有 MigrationV1Test、TimeZoneRoundTripTest 皆未修改）。
- 下一步：S2（員工、裝置與出勤）。
## 2026-11-26　S1 卡 1.1～1.6 完成、卡 1.7 待使用者決定
- 做了：使用者指示「S1 全部做完，要我做決策再停下來問我」（S0 的 0.4～0.12 尚未做，使用者決定先做 S1）。完成 POS 畫面清單、KDS／顧客手機／裝置配對頁畫面清單、六個模組群的 OpenAPI 片段，合併成 docs/contract/openapi.yaml（85 個操作、116 個結構；77 個錯誤碼用了 71 個，其餘 6 個：4 個全域或框架層、METHOD_NOT_ALLOWED／UNSUPPORTED_MEDIA_TYPE 屬框架層、TABLE_UNAVAILABLE 見待決 #19）。以 swagger-parser 驗證（暫存工具，不在專案內）：語法與 $ref 通過、operationId 無重複、所有 x-error-codes 都在 §4.8 列舉內；畫面清單引用的 84 個 REST 操作與合約一致，合約每個操作都至少被一個畫面使用，WebSocket 於 KDS 畫面 K2 說明。
- 動到的程式碼：新增 docs/contract/{screens-pos.md、screens-other.md、merge.sh、openapi.yaml（合併產物）、fragments/00-common、10-staff、20-menu、30-table、40-order、50-kds、60-customer、70-checkout、80-report、90-print.yaml}；更新 02-TODO（1.1～1.6 打勾）。無程式碼。
- 決定：合約以「路徑前置詞決定裝置類型」（/api/staff、/api/pos 限 POS；/api/kds；/api/customer 無裝置憑證；配對為 POST /api/pairing）；每個操作以 x-error-codes 列出「HTTP 狀態 → 錯誤碼」。所有 JSON 欄位、列舉值（如 SQUARE／ROUND／RECTANGLE、STAFF／MANAGER、PENDING／IN_PROGRESS／DONE／CANCELLED）由合約定義，凍結後即為規格。
- 待決：新增 #8～#22（見待決表，每項附建議選項）。另發現規格文字小瑕疵（不需決定）：§4.5 寫「唯一例外：配對 API」，但顧客端 API 本來就不經裝置憑證。
- 測試：無（文件卡）。
- 下一步：使用者回覆 #8～#22 的決定（可說「全採建議」或逐項指定）→ 依決定修改片段、重新合併與驗證、版本改為 1.0.0（凍結）、卡 1.7 打勾 → S1 驗收（使用者確認畫面與流程）。之後回到 S0 的卡 0.4。

## 2026-11-26　卡 0.3 JDBC 與 Hibernate 時區 UTC
- 做了：使用者確認計畫（application.yml 現在建立、時間型別用 Instant、測試實體只放測試目錄）。設定 Hibernate jdbc.time_zone=UTC 與 MySQL 連線時區 UTC（含寫入連線 session）。先跑一次未設定的基準：只有「連線時區」一項失敗（session 為 SYSTEM），其餘靠 Hibernate 6 預設恰好通過；加設定後全過。
- 動到的程式碼：新增 src/main/resources/application.yml；新增 src/test/java/restaurant_pos/shared/TimeZoneRoundTripTest.java、TimeProbeSetting.java（測試專用實體）。
- 決定：時間欄位的 Java 型別沿用 Instant（使用者同意）；application.yml 只放時區設定，其餘由 0.4 擴充。
- 待決：無。
- 測試：本卡 6／6、同模組 7／7、全部 13／13。
- 下一步：使用者回「好」後進卡 0.4（執行環境與啟動檢查、Swagger 開關、日誌輪替）。

## 2026-11-26　卡 0.2 MySQL 8＋Testcontainers＋V1 遷移檔
- 做了：使用者決定待決 #5（V1 只含 S0 三張表，欄位草擬待審）與 #6（加 docker-compose.yml）。新增依賴：Spring Data JPA、Flyway＋flyway-mysql、MySQL 驅動（runtime）、Testcontainers（mysql、junit-jupiter，test）、spring-boot-testcontainers（test）。寫 V1 遷移檔、開發用 compose、遷移測試 7 個。
- 動到的程式碼：修改 pom.xml；新增 src/main/resources/db/migration/V1__s0_base_tables.sql、docker-compose.yml、src/test/java/restaurant_pos/shared/MigrationV1Test.java。
- 決定：V1 欄位草擬（使用者尚未審定，見下）— app_setting（id、setting_key 唯一、setting_value、updated_at）；audit_log（id、occurred_at、business_date、action、actor_employee_id、actor_device_id、target_type、target_id、detail）；device（id、device_type、name、credential_hash 唯一、created_at、revoked_at）。時間皆 DATETIME(6)。mysql:8.0 映像。
- 待決：#7 已結案（使用者選 A，surefire argLine 加 -Dapi.version=1.44，pom 已修改）。
- 測試：本卡 7／7；全部 7／7（本卡為第一張有測試的卡，無前面階段測試）。執行指令：`mvn test`。
- 下一步：卡 0.3（使用者已於同日確認 V1 欄位並同意 commit／push）。

## 2026-11-26　卡 0.1 建立 Spring Boot 骨架（待「好」）
- 做了：使用者確認計畫（依賴只用 spring-boot-starter＋test；編譯目標 Java 17；加 .gitignore；代做開工前事項）。建立 pom.xml（Spring Boot 3.5.6、release 17）、入口類別、九個模組的 package-info.java。開工前事項：四份文件移到 docs/plan/、建立 CLAUDE.md、git init、加 origin（https://github.com/FanYingZuo/restaurant-pos.git，遠端查無 refs）。
- 動到的程式碼：新增 pom.xml、.gitignore、CLAUDE.md、src/main/java/restaurant_pos/RestaurantPosApplication.java、src/main/java/restaurant_pos/{shared,staff,menu,table,order,checkout,kds,customer,report}/package-info.java；搬移 docs/plan/*。
- 決定：spring-boot-maven-plugin 加入 build（不屬新依賴）；無 docs/archive（沒有舊文件可搬）。
- 待決：無。
- 未完成（使用者）：git user.name 未設定，設定檔中的 email 疑似打錯（gmail.comm），起點 commit 與第一次 commit 前需先修正；分支目前為 master，push 時需決定是否改 main。
- 測試：本卡 T1 編譯通過（mvn compile，class 為 major 61 = Java 17）、T2 結構 9／9 通過、T3 入口類別存在；本卡無測試類別，未跑測試。
- 下一步：使用者回「好」後進卡 0.2。

## 2026-11-26　待決 #4 結案
- 做了：使用者提供套件根名稱 `restaurant_pos`；寫入 01-PLAN §3，卡 0.1 的 ⚠ 移除。
- 動到的程式碼：無（文件卡）。
- 決定：套件根名稱 `restaurant_pos`（使用者指定）。注意：Java 允許底線，但慣例套件名用小寫無底線；此為使用者選擇，日後改名需在卡 0.1 前決定，否則牽動所有套件。
- 待決：無。
- 未處理：使用者曾貼 GitHub repo `FanYingZuo/restaurant-pos` 的連結，AI 未讀取（加入工作階段的請求被拒）。規格前提是「從零開始」，若該 repo 已有程式，需使用者先確認其定位，再決定是否衝突。
- 下一步：卡 0.1（建立 Maven 專案骨架）。

## 2026-11-26　待決 #1～#3 結案（採 AI 建議選項）
- 做了：使用者指示採用 AI 建議選項，結案三項待決，並同步修改規格與卡片。
- 動到的程式碼：無（文件卡）。
- 決定：
  - #1 → 選 B：依 §4.8 錯誤碼表推導 HTTP 對照（7 類：400／401／403／404／409／429／500；405、415 為框架層）。已寫入 01-PLAN §4.8。注意：此表由 AI 草擬，採用的是「依現有錯誤碼推導」，不是舊 C5 原表；若日後要對回舊 C5，需另行比對。
  - #2 → 選 A：刪掉第 5 個等待值，等待 5、15、30、60 秒，維持共 5 次嘗試。已寫入 01-PLAN §5 S11。
  - #3 → 選 A：建置工具採 Maven。已寫入 01-PLAN §3。
  - 卡 0.5、11.4 的 ⚠ 已移除；卡 0.1 的 ⚠ 改為等 #4。
- 待決：#4（新增）。
- 下一步：使用者決定 #4 → 搬檔、建 CLAUDE.md → 卡 0.1。

## 2026-11-26　規格說明書 v1
- 做了：把規劃內容整理成四份文件，定版為規格說明書 v1；確認專案從零開始、沒有任何實作。
- 動到的程式碼：無（文件卡）。
- 決定：01-PLAN 為唯一規格來源，決策編號重排為 D-1～D-35；卡 0.1 從「盤點既有程式」改為「建立專案骨架」；S1 移除「與舊規格檔的差異清單」；遷移檔自 V1 起算；03-BOUNDARIES 新增 §3 保密邊界與「沒有既有程式碼」前提。
- 待決：（本次新增 #3，已於同日結案）。
- 下一步：決定 #3 等。

## 2026-11-19　規格問題釘定
- 做了：使用者逐項決定 17 項規格未定問題（WebSocket 憑證帶法、token 保密範圍、ZXing、裝置憑證雜湊、列印重試、前端測試方式、顧客送單歸屬模組、日結 PIN、HTTP 狀態原則、打卡天數算法、方圓桌驗證、顧客單上限錯誤碼、報表 92 天算法、送單內容雜湊格式等）。
- 動到的程式碼：無。
- 決定：全部內容已寫入 `01-PLAN.md` 對應段落，成為 v1 的規格本文，不另外保留問題編號。
- 待決：無。

---

## 待決問題

> 規則：AI 遇到「規格沒寫」或「規格矛盾」→ **只能在下表新增一筆並停下回報**，不可自行決定、不可填「決定」欄。使用者填決定後，由使用者同步更新 `01-PLAN.md`。

| # | 問題 | 擋住的卡 | 建議選項（AI 不可自行採用） | 決定 |
| --- | --- | --- | --- | --- |

### 已結案

| # | 問題 | 決定（2026-11-26，採 AI 建議選項） |
| --- | --- | --- |
| 1 | HTTP 狀態分類對照表缺失 | B：依 §4.8 錯誤碼表推導 7 類對照，已寫入 01-PLAN §4.8 |
| 2 | 列印重試等待值多一個 | A：刪掉第 5 個等待值，共 5 次嘗試，已寫入 01-PLAN §5 S11 |
| 3 | 建置工具未指定 | A：Maven，已寫入 01-PLAN §3 |
| 4 | 套件根名稱未指定 | 使用者決定：`restaurant_pos`，已寫入 01-PLAN §3（同日由使用者提供，非 AI 選項） |
| 5 | V1 遷移檔範圍與欄位未定 | 使用者決定：A（V1 只含 S0 三張表 app_setting、audit_log、device；欄位為 AI 草擬，待使用者審定） |
| 6 | 本機 Docker 未執行；是否加 docker-compose.yml | 使用者決定：加 docker-compose.yml（密碼只從 .env 讀）；Docker Desktop 已啟動 |
| 7 | Testcontainers 與 Docker 29 API 版本不相容 | 使用者決定：A，把 -Dapi.version=1.44 寫進 pom 的 surefire argLine（不改依賴版本）；直接 mvn test 即可，7/7 通過 |
| 8～22 | S1 合約待決（分頁範圍、看板小計、打卡歷史權限、/api/kds 裝置類型、前端路徑、解鎖錯誤碼、冪等重播狀態、取消理由、結束不收費條件、重印 QR 錯誤碼、重印單據挑選、TABLE_UNAVAILABLE、名稱長度、工時報表上限、V1 欄位不一致） | 使用者決定：全部採 AI 建議選項（2026-11-26）。合約已凍結 1.0.0（docs/contract/openapi.yaml）。#22 的承諾：不改 V1，卡 0.8、0.10、0.11 以新遷移檔補 audit_log（前值、後值、理由）與 device（最後使用時間、配對碼）欄位 |
