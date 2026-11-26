# 操作文件

> 範圍：**開發環境**的啟動、跑測試、配對第一台裝置。部署上線（反向代理、HTTPS、備份）不在本規劃內（01-PLAN §1）。
> 機密值（密碼、PIN 密鑰、配對碼、憑證）一律只放環境變數或主控台，**不要寫進任何會被 commit 的檔案**（`.env` 已被 `.gitignore` 排除）。

## 1 事前準備

- JDK：本機任何 17 以上版本皆可（專案以 Java 17 為編譯目標）。
- Maven 3.9 以上。
- Docker Desktop 必須在執行（開發資料庫與整合測試都用它）。

## 2 啟動資料庫（MySQL 8）

在專案根目錄建立 `.env`（只含這一行，值自行設定）：

```
MYSQL_ROOT_PASSWORD=<自行設定的密碼>
```

```
docker compose up -d
```

資料庫名稱為 `restaurant_pos`，連接埠 3306。第一次啟動時應用程式會自動執行 Flyway 遷移建立資料表。

## 3 啟動應用程式（開發環境）

環境變數（01-PLAN §4.9）：

| 環境變數 | 說明 | 開發環境 | 正式環境 |
| --- | --- | --- | --- |
| `POS_ENV` | 執行環境；**未設定即為正式**，只有 `dev` 才是開發 | `dev` | 不設或其他值 |
| `POS_DB_URL` | 資料庫連線，例如 `jdbc:mysql://localhost:3306/restaurant_pos` | 必填 | 必填 |
| `POS_DB_USER`、`POS_DB_PASSWORD` | 資料庫帳密 | 必填 | 必填 |
| `POS_BASE_URL` | 公開基底網址（QR 網址、WebSocket 來源） | 預設 `http://localhost:8080` | 必填，缺則拒絕啟動 |
| `POS_PIN_SECRET` | PIN 密鑰（HMAC）；正式至少 32 字元 | 未設定則用開發密鑰並印警告 | 必填，缺或不足 32 字元則拒絕啟動 |
| `POS_TRUSTED_PROXIES` | 信任代理位址（逗號分隔）；只有來自這些位址的 `X-Forwarded-For` 才被採信 | 選填 | 選填 |
| `POS_FIRST_MANAGER_PIN` | 首位店長的 PIN；未設定則啟動時產生隨機六位數、**只印在主控台一次** | 選填 | 選填 |
| `POS_IMAGE_DIR` | 菜單圖片目錄 | 預設 `data/images` | 預設 `data/images` |
| `POS_LOG_DIR` | 日誌目錄（`pos.log`、`print.log`） | 預設 `logs` | 預設 `logs` |

PowerShell 範例：

```powershell
$env:POS_ENV = "dev"
$env:POS_DB_URL = "jdbc:mysql://localhost:3306/restaurant_pos"
$env:POS_DB_USER = "root"
$env:POS_DB_PASSWORD = "<與 .env 相同>"
mvn spring-boot:run
```

應用程式只綁定 `127.0.0.1:8080`。開發環境的 Swagger：<http://localhost:8080/swagger-ui/index.html>（正式環境該路徑回 404）。

## 4 跑測試

```
mvn test
```

- 需要 Docker 在執行：整合測試以 Testcontainers 自動啟動 `mysql:8.0` 容器（第一次會下載映像，需要一點時間），**不使用 H2**。
- 測試之間共用一個容器，每個測試開始前自動清空資料表。
- 本機 Docker Desktop 29 與 Testcontainers 1.21 的 API 版本相容性，已以 `pom.xml` 的 surefire 設定 `-Dapi.version=1.44` 處理（待決 #7），不需手動設定。

## 5 配對第一台裝置

所有店內 API 都需要裝置憑證（`X-Device-Token`），唯一例外是配對 API。

1. **取得配對碼**：第一次啟動時若沒有任何可用的（已配對且未撤銷）裝置，應用程式會建立一台「首台 POS」，並**只在主控台**印出 8 位配對碼（10 分鐘內有效，不寫入任何日誌）：

   ```
   首台 POS 配對碼（10 分鐘內有效，只顯示這一次）：ABCD2345
   ```

   若錯過或過期：重新啟動應用程式，會替同一台首台 POS 重新產生配對碼。

2. **用配對碼換取憑證**（憑證**只會回傳這一次**，請立即保存）：

   ```
   curl -X POST http://localhost:8080/api/pairing -H "Content-Type: application/json" -d "{\"pairingCode\":\"ABCD2345\"}"
   ```

   回應包含 `deviceToken`。同一個來源 IP 在 10 分鐘內配對錯誤 10 次會被鎖定 10 分鐘。

3. **使用憑證**：之後所有 `/api/pos/**`、`/api/staff/**` 請求帶上標頭 `X-Device-Token: <deviceToken>`。在 Swagger 畫面按右上角 **Authorize**，把憑證填入 `DeviceToken` 即可。

4. **新增其他裝置**（例如廚房平板）：在已配對的 POS 上以管理者解鎖「DEVICE_MANAGE」後建立裝置並取得新配對碼（員工與裝置管理 API 於 S2 完成後可用）。KDS 裝置只能呼叫 `/api/kds/**` 與訂閱 `/ws`。

## 6 日誌

- `logs/pos.log`：應用程式日誌，依日輪替，單檔超過 50 MB 也輪替，保存 90 天。
- `logs/print.log`：列印檔案輸出（S11），輪替規則相同。
- 日誌只記識別碼，不記 PIN、憑證、配對碼、token、密鑰（03-BOUNDARIES §3）。
