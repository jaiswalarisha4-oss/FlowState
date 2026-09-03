package com.flowstate.web;

import com.flowstate.domain.Account;
import com.flowstate.domain.AccountType;
import com.flowstate.domain.User;
import com.flowstate.repository.AccountRepository;
import com.flowstate.repository.UserRepository;
import com.flowstate.security.CurrentUserService;
import com.flowstate.web.dto.AuthDtos.LoginRequest;
import com.flowstate.web.dto.AuthDtos.RegisterRequest;
import com.flowstate.web.dto.AuthDtos.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final CurrentUserService currentUserService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(UserRepository userRepository, AccountRepository accountRepository,
                           PasswordEncoder passwordEncoder, AuthenticationManager authenticationManager,
                           CurrentUserService currentUserService) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req,
                                       HttpServletRequest request, HttpServletResponse response) {
        if (userRepository.existsByEmail(req.email())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("{\"error\":\"An account with this email already exists\"}");
        }
        User user = new User(req.email(), req.displayName(), passwordEncoder.encode(req.password()));
        userRepository.save(user);
        accountRepository.save(new Account(user, "Checking", AccountType.CHECKING, BigDecimal.ZERO));
        accountRepository.save(new Account(user, "Savings", AccountType.SAVINGS, BigDecimal.ZERO));

        authenticate(req.email(), req.password(), request, response);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(user));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req,
                                    HttpServletRequest request, HttpServletResponse response) {
        authenticate(req.email(), req.password(), request, response);
        User user = userRepository.findByEmail(req.email()).orElseThrow();
        return ResponseEntity.ok(toResponse(user));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        User user = currentUserService.getCurrentUser();
        return ResponseEntity.ok(toResponse(user));
    }

    private void authenticate(String email, String password, HttpServletRequest request, HttpServletResponse response) {
        Authentication authRequest = new UsernamePasswordAuthenticationToken(email, password);
        Authentication authResult = authenticationManager.authenticate(authRequest);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authResult);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getRiskTolerance().name());
    }
}
