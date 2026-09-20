package thitkho.userservice.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import thitkho.exception.AppException;
import thitkho.payload.ApiResponse;
import thitkho.userservice.dto.request.ChangePasswordRequest;
import thitkho.userservice.dto.request.LoginRequest;
import thitkho.userservice.dto.request.RegisterRequest;
import thitkho.userservice.dto.request.UpdateProfileRequest;
import thitkho.userservice.dto.response.AuthResponse;
import thitkho.userservice.dto.response.OnlineStatusResponse;
import thitkho.userservice.dto.response.UserResponse;
import thitkho.userservice.exception.AuthErrorCode;
import thitkho.userservice.model.enums.Role;
import thitkho.userservice.service.UserService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final UserService userService;

    // ==================== AUTH ====================

    @PostMapping("/auth/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(userService.register(request)));
    }

    @PostMapping("/auth/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(userService.login(request)));
    }

    @PostMapping("/auth/google")
    public ResponseEntity<ApiResponse<AuthResponse>> loginGoogle(
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success(
                userService.loginGoogle(body.get("idToken"))));
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refreshToken(
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success(
                userService.refreshToken(body.get("refreshToken"))));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            HttpServletRequest request) {
        String token = extractToken(request);
        userService.logout(token);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ==================== PROFILE ====================

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMyProfile(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(userService.getProfile(userId)));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<UserResponse>> getProfile(
            @PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success(userService.getProfile(userId)));
    }

    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateProfile(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                userService.updateProfile(userId, request)));
    }

    @PutMapping("/me/avatar")
    public ResponseEntity<ApiResponse<UserResponse>> updateAvatar(
            @AuthenticationPrincipal String userId,
            @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(ApiResponse.success(
                userService.updateAvatar(userId, file)));
    }

    @PutMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(userId, request);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ==================== ONLINE STATUS ====================

    @GetMapping("/{userId}/status")
    public ResponseEntity<ApiResponse<OnlineStatusResponse>> getOnlineStatus(
            @PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.success(
                userService.getOnlineStatus(userId)));
    }

    @PostMapping("/status/batch")
    public ResponseEntity<ApiResponse<List<OnlineStatusResponse>>> getOnlineStatusBatch(
            @RequestBody List<String> userIds) {
        return ResponseEntity.ok(ApiResponse.success(
                userService.getOnlineStatusByIds(userIds)));
    }

    @GetMapping("/status/online")
    public ResponseEntity<ApiResponse<List<String>>> getAllOnlineUsers() {
        return ResponseEntity.ok(ApiResponse.success(userService.getAllOnlineUserIds()));
    }

    // ==================== ADMIN ====================

    @GetMapping("")
//    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<UserResponse>>> getAllUsers(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(userService.getAllUsers(pageable)));
    }

    @PutMapping("/admin/users/{userId}/ban")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> banUser(@PathVariable String userId) {
        userService.banUser(userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PutMapping("/admin/users/{userId}/unban")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> unbanUser(@PathVariable String userId) {
        userService.unbanUser(userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PutMapping("/admin/users/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> changeRole(
            @PathVariable String userId,
            @RequestBody Map<String, String> body) {
        userService.changeRole(userId, Role.valueOf(body.get("role")));
        return ResponseEntity.ok(ApiResponse.success(null));
    }
    // ==================== USER ====================
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<Page<UserResponse>>> searchUsers(
            @RequestParam String query,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(userService.search(pageable, query)));
    }
    // ==================== HELPER ====================

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        throw new AppException(AuthErrorCode.INVALID_CREDENTIALS);
    }
}