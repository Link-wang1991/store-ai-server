package com.storeai.meeting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storeai.meeting.entity.Meeting;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MeetingRepository extends BaseMapper<Meeting> {
}
