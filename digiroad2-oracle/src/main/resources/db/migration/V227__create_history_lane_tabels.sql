CREATE TABLE LANE_HISTORY (
    ID NUMBER,
    NEW_ID NUMBER,
    OLD_ID NUMBER,
    LANE_CODE NUMBER(20) NOT NULL,
    CREATED_DATE TIMESTAMP(6) NOT NULL,
    CREATED_BY VARCHAR2(128) NOT NULL,
    MODIFIED_DATE TIMESTAMP(6),
    MODIFIED_BY VARCHAR2(128),
    EXPIRED_DATE TIMESTAMP(6),
    EXPIRED_BY VARCHAR2(128),
    VALID_FROM TIMESTAMP(6),
    VALID_TO TIMESTAMP,
    MUNICIPALITY_CODE NUMBER,
    HISTORY_CREATED_DATE TIMESTAMP(6) NOT NULL,
    HISTORY_CREATED_BY VARCHAR2(128) NOT NULL,
    CONSTRAINT lane_history_pk PRIMARY KEY (ID)
);
CREATE INDEX LANE_HISTORY_CODE_INDEX ON LANE_HISTORY (LANE_CODE);
CREATE INDEX LANE_HISTORY_MUN_CODE_IDX ON LANE_HISTORY (MUNICIPALITY_CODE);
CREATE INDEX LANE_HISTORY_VALID_TO_IDX ON LANE_HISTORY (VALID_TO);
CREATE INDEX LANE_HISTORY_NEW_ID_IDX ON LANE_HISTORY (NEW_ID);
CREATE INDEX LANE_HISTORY_OLD_ID_IDX ON LANE_HISTORY (OLD_ID);

CREATE TABLE LANE_HISTORY_POSITION (
    ID NUMBER,
    SIDE_CODE NUMBER(5) NOT NULL,
    START_MEASURE NUMBER(8,3) NOT NULL,
    END_MEASURE NUMBER(8,3) NOT NULL,
    LINK_ID NUMBER(10),
    ADJUSTED_TIMESTAMP NUMBER(38),
    MODIFIED_DATE TIMESTAMP(6),
    CONSTRAINT lane_history_position_pk PRIMARY KEY (ID)
);
CREATE INDEX LANE_HISTORY_POS_LINK_ID_IDX ON LANE_HISTORY_POSITION (LINK_ID);
CREATE INDEX LANE_HIST_POS_LINKID_SIDEC_IDX ON LANE_HISTORY_POSITION (LINK_ID, SIDE_CODE );

CREATE TABLE LANE_HISTORY_LINK (
    LANE_ID NUMBER,
    LANE_POSITION_ID NUMBER,
    CONSTRAINT lane_history_link_pk PRIMARY KEY (LANE_ID, LANE_POSITION_ID),
    CONSTRAINT fk_l_hist_link_lane_history FOREIGN KEY(LANE_ID) REFERENCES LANE_HISTORY(ID),
    CONSTRAINT fk_l_hist_link_lane_hist_pos FOREIGN KEY(LANE_POSITION_ID) REFERENCES LANE_HISTORY_POSITION(ID)
);

CREATE TABLE LANE_HISTORY_ATTRIBUTE (
    ID NUMBER,
    LANE_HISTORY_ID NUMBER(38),
    NAME VARCHAR2(128),
    VALUE VARCHAR2(128),
    REQUIRED CHAR(1) DEFAULT '0',
    CREATED_DATE TIMESTAMP(6),
    CREATED_BY VARCHAR2(128),
    MODIFIED_DATE TIMESTAMP(6),
    MODIFIED_BY VARCHAR2(128),
    CONSTRAINT lane_history_attribute_pk PRIMARY KEY (ID),
    CONSTRAINT fk_l_hist_attrib_lane_hist FOREIGN KEY(LANE_ID) REFERENCES LANE_HISTORY(ID)
);