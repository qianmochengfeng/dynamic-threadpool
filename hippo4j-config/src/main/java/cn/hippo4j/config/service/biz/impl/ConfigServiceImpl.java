package cn.hippo4j.config.service.biz.impl;

import cn.hippo4j.common.config.ApplicationContextHolder;
import cn.hippo4j.common.toolkit.ConditionUtil;
import cn.hippo4j.common.toolkit.ContentUtil;
import cn.hippo4j.common.toolkit.Md5Util;
import cn.hippo4j.config.event.LocalDataChangeEvent;
import cn.hippo4j.config.mapper.ConfigInfoMapper;
import cn.hippo4j.config.model.ConfigAllInfo;
import cn.hippo4j.config.model.ConfigInfoBase;
import cn.hippo4j.config.service.ConfigChangePublisher;
import cn.hippo4j.config.service.biz.ConfigService;
import cn.hippo4j.tools.logrecord.annotation.LogRecord;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.toolkit.SqlHelper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Config service impl.
 *
 * @author chen.ma
 * @date 2021/6/20 15:21
 */
@Slf4j
@Service
@AllArgsConstructor
public class ConfigServiceImpl implements ConfigService {

    private final ConfigInfoMapper configInfoMapper;

    @Override
    public ConfigAllInfo findConfigAllInfo(String tpId, String itemId, String tenantId) {
        LambdaQueryWrapper<ConfigAllInfo> wrapper = Wrappers.lambdaQuery(ConfigAllInfo.class)
                .eq(!StringUtils.isBlank(tpId), ConfigAllInfo::getTpId, tpId)
                .eq(!StringUtils.isBlank(itemId), ConfigAllInfo::getItemId, itemId)
                .eq(!StringUtils.isBlank(tenantId), ConfigAllInfo::getTenantId, tenantId);

        ConfigAllInfo configAllInfo = configInfoMapper.selectOne(wrapper);
        return configAllInfo;
    }

    @Override
    public void insertOrUpdate(String identify, ConfigAllInfo configInfo) {
        LambdaQueryWrapper<ConfigAllInfo> queryWrapper = Wrappers.lambdaQuery(ConfigAllInfo.class)
                .eq(ConfigAllInfo::getTenantId, configInfo.getTenantId())
                .eq(ConfigInfoBase::getItemId, configInfo.getItemId())
                .eq(ConfigInfoBase::getTpId, configInfo.getTpId());
        ConfigAllInfo existConfig = configInfoMapper.selectOne(queryWrapper);

        ConfigServiceImpl configService = ApplicationContextHolder.getBean(this.getClass());
        configInfo.setCapacity(getQueueCapacityByType(configInfo));

        try {
            ConditionUtil
                    .condition(
                            existConfig == null,
                            () -> configService.addConfigInfo(configInfo),
                            () -> configService.updateConfigInfo(configInfo)
                    );
        } catch (Exception ex) {
            updateConfigInfo(configInfo);
        }

        ConfigChangePublisher.notifyConfigChange(new LocalDataChangeEvent(identify, ContentUtil.getGroupKey(configInfo)));
    }

    public Long addConfigInfo(ConfigAllInfo config) {
        config.setContent(ContentUtil.getPoolContent(config));
        config.setMd5(Md5Util.getTpContentMd5(config));

        try {
            if (SqlHelper.retBool(configInfoMapper.insert(config))) {
                return config.getId();
            }
        } catch (Exception ex) {
            log.error("[db-error] message :: {}", ex.getMessage(), ex);
            throw ex;
        }

        return null;
    }

    @LogRecord(
            bizNo = "{{#config.itemId}}_{{#config.tpId}}",
            category = "THREAD_POOL_UPDATE",
            success = "核心线程: {{#config.coreSize}}, 最大线程: {{#config.maxSize}}, 队列类型: {{#config.queueType}}, 队列容量: {{#config.capacity}}, 拒绝策略: {{#config.rejectedType}}",
            detail = "{{#config.toString()}}"
    )
    public void updateConfigInfo(ConfigAllInfo config) {
        LambdaUpdateWrapper<ConfigAllInfo> wrapper = Wrappers.lambdaUpdate(ConfigAllInfo.class)
                .eq(ConfigAllInfo::getTpId, config.getTpId())
                .eq(ConfigAllInfo::getItemId, config.getItemId())
                .eq(ConfigAllInfo::getTenantId, config.getTenantId());

        config.setGmtCreate(null);
        config.setContent(ContentUtil.getPoolContent(config));
        config.setMd5(Md5Util.getTpContentMd5(config));

        try {
            configInfoMapper.update(config, wrapper);
        } catch (Exception ex) {
            log.error("[db-error] message :: {}", ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * 根据队列类型获取队列大小.
     * <p>
     * 不支持设置队列大小 {@link SynchronousQueue} {@link LinkedTransferQueue}
     *
     * @param config
     * @return
     */
    private Integer getQueueCapacityByType(ConfigAllInfo config) {
        int queueCapacity;
        switch (config.getQueueType()) {
            case 5:
                queueCapacity = Integer.MAX_VALUE;
                break;
            default:
                queueCapacity = config.getCapacity();
                break;
        }

        List<Integer> queueTypes = Stream.of(1, 2, 3, 6, 9).collect(Collectors.toList());
        boolean setDefaultFlag = queueTypes.contains(config.getQueueType()) && (config.getCapacity() == null || Objects.equals(config.getCapacity(), 0));
        if (setDefaultFlag) {
            queueCapacity = 1024;
        }

        return queueCapacity;
    }

}
