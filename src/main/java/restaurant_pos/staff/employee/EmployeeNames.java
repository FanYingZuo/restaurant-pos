package restaurant_pos.staff.employee;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提供給其他模組的「批次取員工名稱」（§4.1、S2「提供給其他模組」）。
 * 已停用員工名稱仍回傳（報表與稽核需要顯示）。查不到的編號不在結果中。
 */
@Service
public class EmployeeNames {

    private final EmployeeRepository employees;

    public EmployeeNames(EmployeeRepository employees) {
        this.employees = employees;
    }

    @Transactional(readOnly = true)
    public Map<Long, String> namesByIds(Collection<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        for (Employee e : employees.findByIdIn(ids.stream().filter(java.util.Objects::nonNull).distinct().toList())) {
            result.put(e.getId(), e.getName());
        }
        return result;
    }
}
