package com.example.payments.api;

import com.example.payments.api.dto.CallbackResult;
import com.example.payments.api.dto.ProviderCallbackRequest;
import com.example.payments.service.CallbackApplyResult;
import com.example.payments.service.CallbackService;
import com.example.payments.service.DuplicateEventException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/provider-callbacks")
public class ProviderCallbackController {

    private final CallbackService callbackService;

    public ProviderCallbackController(CallbackService callbackService) {
        this.callbackService = callbackService;
    }

    @PostMapping
    public ResponseEntity<CallbackResult> receive(@Valid @RequestBody ProviderCallbackRequest request) {
        try {
            CallbackApplyResult result = callbackService.handle(request);
            return ResponseEntity.ok(new CallbackResult(result));
        } catch (DuplicateEventException e) {
            // Two requests carrying the same brand-new eventId can race past the duplicate
            // check. Only that insert conflict is acknowledged as a duplicate; other
            // integrity failures still surface as server errors.
            return ResponseEntity.ok(new CallbackResult(CallbackApplyResult.DUPLICATE));
        }
    }
}
