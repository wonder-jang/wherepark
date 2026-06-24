package com.wonder.wherepark.data.model;

/** §19.4 state_logs 모델. 상태 변경 디버깅 로그(사용자 미노출). */
public class StateLog {

    public static final long NO_ID = -1L;

    public long id = NO_ID;
    public String eventType;
    public String previousState;
    public String newState;
    public String eventTime;
    public boolean isStabilized = false;
    public Integer stabilizationSeconds;
    public String memo;
}
