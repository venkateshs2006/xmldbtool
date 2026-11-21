package com.bnpp.controller;

import com.bnpp.model.LoginRequest;
import com.bnpp.model.LoginResponse;
import com.bnpp.model.User;
import com.bnpp.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest req) {
        if (req == null || req.getUsername() == null || req.getPassword() == null) {
            return ResponseEntity.badRequest().body(new LoginResponse(false, null, "Username and password required.", null));
        }

        Optional<User> userOpt = userService.authenticate(req.getUsername(), req.getPassword());
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            String token = "jwt-token-" + user.getUsername() + "-" + System.currentTimeMillis();
            LoginResponse resp = new LoginResponse(true, token, "Login successful.", user);
            return ResponseEntity.ok(resp);
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new LoginResponse(false, null, "Invalid username or password.", null));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        return ResponseEntity.ok(Map.of("success", true, "message", "Logged out successfully."));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(@RequestHeader(value = "Authorization", required = false) String token) {
        User user = userService.validateToken(token);
        if (user != null) {
            return ResponseEntity.ok(user);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Not authenticated"));
    }
}
