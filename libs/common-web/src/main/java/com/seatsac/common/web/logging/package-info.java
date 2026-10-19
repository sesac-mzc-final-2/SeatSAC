/**
 * JSON 로그 설정은 resources/logback-common.xml 에 있다. 서비스의 logback-spring.xml 에서
 * {@code <include resource="logback-common.xml"/>} 로 가져온다.
 * 필드: timestamp(+09:00) · level · service · traceId · userId · message · error
 */
package com.seatsac.common.web.logging;
