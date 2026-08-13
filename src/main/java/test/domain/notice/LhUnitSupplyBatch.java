package test.domain.notice;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import test.domain.source.SourceSystem;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 마이홈 공고버전에 LH 15056765(분양임대공고별 공급정보)가 나중에 보태는 불변 aggregate.
 *
 * <p>{@link LhNoticeSupplement}(15057999)와 요청 파라미터가 같지만 별도 호출·별도 데이터셋이라
 * 따로 뒀다. {@code LhNoticeSupplement}에 다섯 번째 자식으로 얹지 않은 이유는, 그 엔티티가
 * "저장 후에는 자식을 추가할 수 없다"는 불변식을 이미 갖고 있어서다({@code requireNew()}) — 15057999와
 * 15056765는 서로 다른 시점에 따로 실패·재시도될 수 있으므로 하나의 트랜잭션·하나의 aggregate로
 * 묶으면 한쪽 실패가 이미 성공한 다른쪽 데이터까지 통째로 날린다.
 *
 * <p>한 {@code PAN_ID} 응답에 단지가 여러 곳 섞여 나온다({@link LhUnitSupply#getComplexLabel()} 참고).
 */
@Entity
@Table(
        name = "lh_unit_supply_batch",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_lh_unit_supply_batch_version",
                columnNames = "notice_version_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LhUnitSupplyBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_version_id", nullable = false)
    private NoticeVersion noticeVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_system", nullable = false, length = 30)
    private SourceSystem sourceSystem;

    /** 원천 panId. {@link LhNoticeSupplement#getSourcePanId()}와 같은 값이다. */
    @Column(name = "source_pan_id", length = 50)
    private String sourcePanId;

    @Column(name = "requested_connection_system_division_code", length = 10)
    private String requestedConnectionSystemDivisionCode;

    @Column(name = "requested_upper_announcement_type_code", length = 10)
    private String requestedUpperAnnouncementTypeCode;

    @Column(name = "requested_announcement_type_code", length = 10)
    private String requestedAnnouncementTypeCode;

    @Column(name = "requested_supply_info_type_code", length = 10)
    private String requestedSupplyInfoTypeCode;

    @Column(name = "source_responded_at")
    private LocalDateTime sourceRespondedAt;

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;

    /**
     * 응답에 {@code dsList01} 키 자체가 있었는지. {@link LhNoticeSupplement#isComplexDetailDatasetPresent()}와
     * 같은 이유로 행 개수(0건)와 구분한다.
     */
    @Column(name = "unit_supply_dataset_present", nullable = false)
    private boolean unitSupplyDatasetPresent;

    @OneToMany(mappedBy = "unitSupplyBatch", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<LhUnitSupply> unitSupplies = new ArrayList<>();

    public LhUnitSupplyBatch(NoticeVersion noticeVersion,
                             SourceSystem sourceSystem,
                             String sourcePanId,
                             String requestedConnectionSystemDivisionCode,
                             String requestedUpperAnnouncementTypeCode,
                             String requestedAnnouncementTypeCode,
                             String requestedSupplyInfoTypeCode,
                             LocalDateTime sourceRespondedAt,
                             LocalDateTime fetchedAt,
                             boolean unitSupplyDatasetPresent) {
        this.noticeVersion = noticeVersion;
        this.sourceSystem = sourceSystem;
        this.sourcePanId = sourcePanId;
        this.requestedConnectionSystemDivisionCode = requestedConnectionSystemDivisionCode;
        this.requestedUpperAnnouncementTypeCode = requestedUpperAnnouncementTypeCode;
        this.requestedAnnouncementTypeCode = requestedAnnouncementTypeCode;
        this.requestedSupplyInfoTypeCode = requestedSupplyInfoTypeCode;
        this.sourceRespondedAt = sourceRespondedAt;
        this.fetchedAt = fetchedAt;
        this.unitSupplyDatasetPresent = unitSupplyDatasetPresent;
    }

    public void addUnitSupply(int displayOrder,
                              String complexLabel,
                              String typeName,
                              BigDecimal exclusiveArea,
                              BigDecimal supplyArea,
                              Integer totalUnitCount,
                              Integer suppliedUnitCount) {
        requireNew();
        unitSupplies.add(new LhUnitSupply(this, displayOrder, complexLabel, typeName,
                exclusiveArea, supplyArea, totalUnitCount, suppliedUnitCount));
    }

    public List<LhUnitSupply> getUnitSupplies() {
        return List.copyOf(unitSupplies);
    }

    private void requireNew() {
        if (id != null) {
            throw new IllegalStateException("저장된 공급정보 배치는 수정할 수 없습니다.");
        }
    }
}
