package test.domain.housing;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import test.domain.source.SourceSystem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 15059475를 전국 단위로 모두 읽은 현재 스냅샷. */
@Entity
@Table(name = "lh_lease_info_batch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LhLeaseInfoBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 30)
    private SourceSystem sourceSystem;

    @Column(name = "source_responded_at")
    private LocalDateTime sourceRespondedAt;

    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<LhLeaseInfo> leaseInfos = new ArrayList<>();

    public LhLeaseInfoBatch(SourceSystem sourceSystem, LocalDateTime sourceRespondedAt, LocalDateTime fetchedAt) {
        this.sourceSystem = sourceSystem;
        this.sourceRespondedAt = sourceRespondedAt;
        this.fetchedAt = fetchedAt;
    }

    public void addLeaseInfo(int displayOrder,
                             String areaName,
                             String supplyTypeName,
                             String complexLabel,
                             Integer complexTotalUnitCount,
                             BigDecimal exclusiveArea,
                             Integer totalUnitCount) {
        leaseInfos.add(new LhLeaseInfo(this, displayOrder, areaName, supplyTypeName, complexLabel,
                complexTotalUnitCount, exclusiveArea, totalUnitCount));
    }

    public List<LhLeaseInfo> getLeaseInfos() {
        return List.copyOf(leaseInfos);
    }
}
