package restaurant_pos.staff.employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 員工。PIN 只存 HMAC 雜湊；停用為軟刪除並清空 PIN 雜湊。
 * toString 不含任何雜湊（03-BOUNDARIES §3.3）。
 */
@Entity
@Table(name = "employee")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EmployeeRole role;

    @Column(name = "pin_hash", length = 64)
    private String pinHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    protected Employee() {
    }

    public Employee(String name, EmployeeRole role, String pinHash, Instant createdAt) {
        this.name = name;
        this.role = role;
        this.pinHash = pinHash;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public EmployeeRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return deactivatedAt == null;
    }

    public boolean isManager() {
        return role == EmployeeRole.MANAGER;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void changeRole(EmployeeRole newRole) {
        this.role = newRole;
    }

    public void changePin(String newPinHash) {
        this.pinHash = newPinHash;
    }

    /** 停用：軟刪除並清空 PIN 雜湊（之後驗證 PIN 回「PIN 錯誤」，不洩漏已停用）。 */
    public void deactivate(Instant now) {
        this.deactivatedAt = now;
        this.pinHash = null;
    }

    @Override
    public String toString() {
        return "Employee[id=" + id + ", role=" + role + ", active=" + isActive() + "]";
    }
}
