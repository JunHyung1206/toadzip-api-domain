package test.domain.housing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HousingProviderAgencyRepository extends JpaRepository<HousingProviderAgency, Long> {

    Optional<HousingProviderAgency> findByCode(String code);
}
