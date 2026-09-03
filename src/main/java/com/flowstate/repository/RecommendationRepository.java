package com.flowstate.repository;

import com.flowstate.domain.Recommendation;
import com.flowstate.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
    List<Recommendation> findByUserOrderByGeneratedAtDesc(User user);
}
