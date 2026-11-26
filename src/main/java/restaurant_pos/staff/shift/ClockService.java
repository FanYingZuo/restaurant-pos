package restaurant_pos.staff.shift;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import restaurant_pos.shared.error.ApiException;
import restaurant_pos.shared.error.ErrorCode;
import restaurant_pos.shared.security.PinVerifier;
import restaurant_pos.shared.security.VerifiedEmployee;
import restaurant_pos.shared.setting.AppSettingService;
import restaurant_pos.shared.setting.Setting;
import restaurant_pos.shared.time.BusinessDayService;
import restaurant_pos.staff.employee.Employee;
import restaurant_pos.staff.employee.EmployeeNames;
import restaurant_pos.staff.employee.EmployeeRepository;
import restaurant_pos.staff.shift.ShiftDtos.ClockHistory;
import restaurant_pos.staff.shift.ShiftDtos.ClockResult;
import restaurant_pos.staff.shift.ShiftDtos.ShiftBrief;
import restaurant_pos.staff.shift.ShiftDtos.ShiftRecord;

/**
 * 打卡（S2）：
 * <ul>
 *   <li>兩次打卡至少間隔 60 秒（設定 clock.min.interval.seconds）；上班中不能再上班。</li>
 *   <li>沒上班就下班 → 另記「上班未打卡」班次，不計工時。</li>
 *   <li>前一營業日忘記下班 → 該班「下班未打卡」（查詢時判斷），不計工時、不擋今天打卡。</li>
 *   <li>分鐘數無條件捨去；查詢狀態只驗 PIN、不打卡、不受間隔限制。</li>
 * </ul>
 * 同一位員工的打卡先鎖員工列，避免同時兩次上班。
 */
@Service
public class ClockService {

    private final PinVerifier pins;
    private final EmployeeRepository employees;
    private final WorkShiftRepository shifts;
    private final BusinessDayService time;
    private final AppSettingService settings;
    private final EmployeeNames names;
    private final TransactionTemplate tx;

    public ClockService(PinVerifier pins, EmployeeRepository employees, WorkShiftRepository shifts,
            BusinessDayService time, AppSettingService settings, EmployeeNames names, TransactionTemplate tx) {
        this.pins = pins;
        this.employees = employees;
        this.shifts = shifts;
        this.time = time;
        this.settings = settings;
        this.names = names;
        this.tx = tx;
    }

    /**
     * 注意併發：資料庫為 REPEATABLE READ，交易內第一次「普通查詢」會建立快照。
     * 所以 PIN 驗證放在交易外，交易內的**第一個動作**就是鎖員工列；鎖到之後再讀班次，才看得到別人剛提交的結果（§4.6）。
     */
    public ClockResult clockIn(String pin) {
        VerifiedEmployee verified = pins.verify(pin);
        return tx.execute(status -> doClockIn(verified.id()));
    }

    public ClockResult clockOut(String pin) {
        VerifiedEmployee verified = pins.verify(pin);
        return tx.execute(status -> doClockOut(verified.id()));
    }

    private ClockResult doClockIn(Long employeeId) {
        Employee employee = lockActive(employeeId);
        Instant now = time.now();
        LocalDate today = time.businessDateOf(now);
        checkInterval(employee.getId(), now);
        if (findOnDuty(employee.getId(), today).isPresent()) {
            throw new ApiException(ErrorCode.ALREADY_CLOCKED_IN);
        }
        shifts.saveAndFlush(WorkShift.clockedIn(employee.getId(), today, now));
        return result(employee, today, List.of());
    }

    private ClockResult doClockOut(Long employeeId) {
        Employee employee = lockActive(employeeId);
        Instant now = time.now();
        LocalDate today = time.businessDateOf(now);
        checkInterval(employee.getId(), now);
        Optional<WorkShift> onDuty = findOnDuty(employee.getId(), today);
        if (onDuty.isPresent()) {
            onDuty.get().clockOut(now);
            shifts.saveAndFlush(onDuty.get());
            return result(employee, today, List.of());
        }
        shifts.saveAndFlush(WorkShift.clockedOutWithoutIn(employee.getId(), today, now));
        return result(employee, today, List.of(ClockWarning.MISSING_CLOCK_IN));
    }

    /** 查詢目前狀態：只驗 PIN，不打卡。 */
    @Transactional(readOnly = true)
    public ClockResult status(String pin) {
        VerifiedEmployee verified = pins.verify(pin);
        Employee employee = employees.findById(verified.id()).orElseThrow(() -> new ApiException(ErrorCode.INVALID_PIN));
        LocalDate today = time.today();
        return result(employee, today, List.of());
    }

    /** 打卡歷史：天數以營業日計（含今天往前算），1～31、預設 2；新的在前。 */
    @Transactional(readOnly = true)
    public ClockHistory history(Long employeeId, Integer days) {
        int d = days == null ? 2 : days;
        if (d < 1 || d > 31) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        if (employeeId != null && !employees.existsById(employeeId)) {
            throw new ApiException(ErrorCode.EMPLOYEE_NOT_FOUND);
        }
        LocalDate today = time.today();
        LocalDate from = today.minusDays(d - 1L);
        List<WorkShift> found = shifts.findHistory(from, today, employeeId);
        Map<Long, String> nameMap = names.namesByIds(found.stream().map(WorkShift::getEmployeeId).toList());
        List<ShiftRecord> records = found.stream()
                .map(s -> ShiftRecord.of(s, today, nameMap.getOrDefault(s.getEmployeeId(), "")))
                .toList();
        return new ClockHistory(d, from, today, records);
    }

    // ---- 內部 ----

    private Employee lockActive(Long employeeId) {
        Employee employee = employees.lockById(employeeId).orElseThrow(() -> new ApiException(ErrorCode.INVALID_PIN));
        if (!employee.isActive()) {
            throw new ApiException(ErrorCode.INVALID_PIN);
        }
        return employee;
    }

    private void checkInterval(Long employeeId, Instant now) {
        Instant lastIn = shifts.maxClockIn(employeeId).orElse(null);
        Instant lastOut = shifts.maxClockOut(employeeId).orElse(null);
        Instant last = lastIn == null ? lastOut : (lastOut == null || lastIn.isAfter(lastOut) ? lastIn : lastOut);
        if (last == null) {
            return;
        }
        int seconds = settings.getPositiveInt(Setting.CLOCK_MIN_INTERVAL_SECONDS);
        if (now.isBefore(last.plusSeconds(seconds))) {
            throw new ApiException(ErrorCode.CLOCK_TOO_FAST);
        }
    }

    /** 今天營業日、已上班未下班的班次。 */
    private Optional<WorkShift> findOnDuty(Long employeeId, LocalDate today) {
        return shifts.findOpenShifts(employeeId).stream().filter(s -> s.getBusinessDate().equals(today)).findFirst();
    }

    private ClockResult result(Employee employee, LocalDate today, List<ClockWarning> extraWarnings) {
        Long id = employee.getId();
        List<WorkShift> todays = shifts.findByEmployeeIdAndBusinessDateOrderByIdAsc(id, today);
        List<WorkShift> open = shifts.findOpenShifts(id);
        boolean onDuty = open.stream().anyMatch(s -> s.getBusinessDate().equals(today));
        List<WorkShift> latest = shifts.findLatest(id, PageRequest.of(0, 1));
        ShiftState state = onDuty ? ShiftState.ON_DUTY
                : latest.isEmpty() ? ShiftState.COMPLETE : latest.get(0).stateOn(today);

        List<ClockWarning> warnings = new ArrayList<>(extraWarnings);
        if (open.stream().anyMatch(s -> s.getBusinessDate().equals(today.minusDays(1)))) {
            warnings.add(ClockWarning.MISSING_CLOCK_OUT);
        }
        if (extraWarnings.isEmpty() && !latest.isEmpty() && !onDuty
                && latest.get(0).stateOn(today) == ShiftState.MISSING_CLOCK_IN
                && latest.get(0).getBusinessDate().equals(today)) {
            warnings.add(ClockWarning.MISSING_CLOCK_IN);
        }

        int total = todays.stream().map(WorkShift::minutes).filter(m -> m != null).mapToInt(Integer::intValue).sum();
        return new ClockResult(id, employee.getName(), state,
                onDuty ? ClockAction.CLOCK_OUT : ClockAction.CLOCK_IN, List.copyOf(warnings),
                todays.stream().map(s -> ShiftBrief.of(s, today)).toList(), total);
    }
}
