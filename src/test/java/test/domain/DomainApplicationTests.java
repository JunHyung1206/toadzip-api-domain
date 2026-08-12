package test.domain;

import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 개발용 DB 는 data/ 아래 H2 파일이다. 이 테스트는 실제 설정을 그대로 읽으므로
 * 그냥 두면 테스트를 돌릴 때마다 개발 데이터에 손을 댄다. 데이터소스만 인메모리로 갈아끼운다.
 */
@SpringBootTest
@AutoConfigureTestDatabase
class DomainApplicationTests {

	@Test
	void contextLoads() {
	}

}
