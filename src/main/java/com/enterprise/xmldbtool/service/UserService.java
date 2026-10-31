package com.enterprise.xmldbtool.service;

import com.enterprise.xmldbtool.config.AppSchemaInitializer;
import com.enterprise.xmldbtool.model.LoginResponse;
import com.enterprise.xmldbtool.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final JdbcTemplate jdbcTemplate;

    // Active token session map: token -> User
    private final Map<String, User> activeSessions = new ConcurrentHashMap<>();

    private final RowMapper<User> userRowMapper = new RowMapper<>() {
        @Override
        public User mapRow(ResultSet rs, int rowNum) throws SQLException {
            User u = new User();
            u.setId(rs.getLong("id"));
            u.setUsername(rs.getString("username"));
            u.setFullName(rs.getString("full_name"));
            u.setEmail(rs.getString("email"));
            u.setRole(rs.getString("role"));
            u.setEnabled(rs.getBoolean("enabled"));
            if (rs.getTimestamp("created_at") != null) {
                u.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            if (rs.getTimestamp("updated_at") != null) {
                u.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            return u;
        }
    };

    public UserService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public LoginResponse login(String username, String password) {
        if (username == null || password == null) {
            return new LoginResponse(false, "Username and password are required.", null, null);
        }

        String hash = AppSchemaInitializer.hashPassword(password);
        String sql = "SELECT * FROM users WHERE LOWER(username) = LOWER(?) AND password_hash = ?";

        List<User> list;
        try {
            list = jdbcTemplate.query(sql, userRowMapper, username.trim(), hash);
        } catch (Exception ex) {
            list = jdbcTemplate.query("SELECT * FROM app_users WHERE LOWER(username) = LOWER(?) AND password_hash = ?", userRowMapper, username.trim(), hash);
        }

        if (list.isEmpty()) {
            return new LoginResponse(false, "Invalid username or password.", null, null);
        }

        User user = list.get(0);
        if (!user.isEnabled()) {
            return new LoginResponse(false, "Account is disabled. Please contact your system administrator.", null, null);
        }

        String token = UUID.randomUUID().toString();
        activeSessions.put(token, user);
        log.info("User '{}' ({}) successfully logged in. Token generated.", user.getUsername(), user.getRole());

        return new LoginResponse(true, "Login successful.", token, user);
    }

    public void logout(String token) {
        if (token != null) {
            User removed = activeSessions.remove(token.replace("Bearer ", "").trim());
            if (removed != null) {
                log.info("User '{}' logged out.", removed.getUsername());
            }
        }
    }

    public User validateToken(String token) {
        if (token == null) return null;
        String clean = token.replace("Bearer ", "").trim();
        return activeSessions.get(clean);
    }

    public List<User> getAllUsers() {
        try {
            return jdbcTemplate.query("SELECT * FROM users ORDER BY id ASC", userRowMapper);
        } catch (Exception ex) {
            return jdbcTemplate.query("SELECT * FROM app_users ORDER BY id ASC", userRowMapper);
        }
    }

    public User getUserById(Long id) {
        List<User> list;
        try {
            list = jdbcTemplate.query("SELECT * FROM users WHERE id = ?", userRowMapper, id);
        } catch (Exception ex) {
            list = jdbcTemplate.query("SELECT * FROM app_users WHERE id = ?", userRowMapper, id);
        }
        return list.isEmpty() ? null : list.get(0);
    }

    public User createUser(User user) {
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            throw new IllegalArgumentException("Username is required.");
        }
        if (user.getPassword() == null || user.getPassword().trim().isEmpty()) {
            throw new IllegalArgumentException("Password is required.");
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE LOWER(username) = LOWER(?)",
                Integer.class,
                user.getUsername().trim()
        );
        if (count != null && count > 0) {
            throw new IllegalArgumentException("Username '" + user.getUsername() + "' is already in use.");
        }

        String role = user.getRole() != null ? user.getRole().toUpperCase() : "OPERATOR";
        String hash = AppSchemaInitializer.hashPassword(user.getPassword());

        jdbcTemplate.update(
                "INSERT INTO users (username, password_hash, full_name, email, role, enabled) VALUES (?, ?, ?, ?, ?, ?)",
                user.getUsername().trim(),
                hash,
                user.getFullName(),
                user.getEmail(),
                role,
                user.isEnabled()
        );

        try {
            jdbcTemplate.update(
                    "INSERT INTO app_users (username, password_hash, full_name, email, role, enabled) VALUES (?, ?, ?, ?, ?, ?)",
                    user.getUsername().trim(),
                    hash,
                    user.getFullName(),
                    user.getEmail(),
                    role,
                    user.isEnabled()
            );
        } catch (Exception ignored) {}

        return jdbcTemplate.queryForObject(
                "SELECT * FROM users WHERE LOWER(username) = LOWER(?)",
                userRowMapper,
                user.getUsername().trim()
        );
    }

    public User updateUser(Long id, User user) {
        User existing = getUserById(id);
        if (existing == null) {
            throw new IllegalArgumentException("User with ID " + id + " does not exist.");
        }

        String fullName = user.getFullName() != null ? user.getFullName() : existing.getFullName();
        String email = user.getEmail() != null ? user.getEmail() : existing.getEmail();
        String role = user.getRole() != null ? user.getRole().toUpperCase() : existing.getRole();
        boolean enabled = user.isEnabled();

        if (user.getPassword() != null && !user.getPassword().trim().isEmpty()) {
            String newHash = AppSchemaInitializer.hashPassword(user.getPassword().trim());
            jdbcTemplate.update(
                    "UPDATE users SET full_name = ?, email = ?, role = ?, enabled = ?, password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    fullName, email, role, enabled, newHash, id
            );
            try {
                jdbcTemplate.update(
                        "UPDATE app_users SET full_name = ?, email = ?, role = ?, enabled = ?, password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                        fullName, email, role, enabled, newHash, id
                );
            } catch (Exception ignored) {}
        } else {
            jdbcTemplate.update(
                    "UPDATE users SET full_name = ?, email = ?, role = ?, enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    fullName, email, role, enabled, id
            );
            try {
                jdbcTemplate.update(
                        "UPDATE app_users SET full_name = ?, email = ?, role = ?, enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                        fullName, email, role, enabled, id
                );
            } catch (Exception ignored) {}
        }

        return getUserById(id);
    }

    public boolean deleteUser(Long id) {
        User existing = getUserById(id);
        if (existing == null) return false;
        if ("admin".equalsIgnoreCase(existing.getUsername())) {
            throw new IllegalArgumentException("The primary administrator account 'admin' cannot be deleted.");
        }
        int rows = jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
        try {
            jdbcTemplate.update("DELETE FROM app_users WHERE id = ?", id);
        } catch (Exception ignored) {}
        return rows > 0;
    }
}
