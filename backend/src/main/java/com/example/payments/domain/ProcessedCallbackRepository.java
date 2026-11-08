package com.example.payments.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedCallbackRepository extends JpaRepository<ProcessedCallback, String> {
}
