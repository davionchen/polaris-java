/*
 * Tencent is pleased to support the open source community by making Polaris available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.tencent.polaris.plugins.outlier.detector.udp;

import com.tencent.polaris.api.config.consumer.OutlierDetectionConfig;
import com.tencent.polaris.api.config.plugin.PluginConfigProvider;
import com.tencent.polaris.api.config.verify.DefaultValues;
import com.tencent.polaris.api.config.verify.Verifier;
import com.tencent.polaris.api.exception.PolarisException;
import com.tencent.polaris.api.plugin.PluginType;
import com.tencent.polaris.api.plugin.common.InitContext;
import com.tencent.polaris.api.plugin.common.PluginTypes;
import com.tencent.polaris.api.plugin.compose.Extensions;
import com.tencent.polaris.api.plugin.detect.HealthChecker;
import com.tencent.polaris.api.pojo.DetectResult;
import com.tencent.polaris.api.pojo.Instance;
import com.tencent.polaris.api.pojo.RetStatus;
import com.tencent.polaris.api.utils.ConversionUtils;
import com.tencent.polaris.api.utils.StringUtils;
import com.tencent.polaris.logging.LoggerFactory;
import com.tencent.polaris.specification.api.v1.fault.tolerance.FaultDetectorProto.FaultDetectRule;
import com.tencent.polaris.specification.api.v1.fault.tolerance.FaultDetectorProto.FaultDetectRule.Protocol;
import com.tencent.polaris.specification.api.v1.fault.tolerance.FaultDetectorProto.UdpProtocolConfig;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;

/**
 * UdpOutlierDetector.java
 *
 * @author andrewshan
 * @date 2019/9/19
 */
public class UdpHealthChecker implements HealthChecker, PluginConfigProvider {

    private static final Logger LOG = LoggerFactory.getLogger(UdpHealthChecker.class);

    private UdpProtocolConfig config;

    @Override
    public DetectResult detectInstance(Instance instance, FaultDetectRule faultDetectRule) throws PolarisException {
        String host = instance.getHost();
        int port = instance.getPort();

        UdpProtocolConfig curConfig = config;

        int timeoutMs = DEFAULT_TIMEOUT_MILLI;
        if (null != faultDetectRule && faultDetectRule.getProtocol() == Protocol.TCP) {
            if (faultDetectRule.getTimeout() > 0) {
                timeoutMs = faultDetectRule.getTimeout();
            }
            if (faultDetectRule.getPort() > 0) {
                port = faultDetectRule.getPort();
            }
            curConfig = faultDetectRule.getUdpConfig();
        }
        byte[] sendBytes = null;
        int maxLength = 0;
        Set<String> expectRecvStrs = new HashSet<>();
        if (null != curConfig) {
            if (StringUtils.isNotBlank(curConfig.getSend())) {
                sendBytes = ConversionUtils.anyStringToByte(curConfig.getSend());
            }
            for (String receiveStr : curConfig.getReceiveList()) {
                byte[] receiveBytes = ConversionUtils.anyStringToByte(receiveStr);
                if (null != receiveBytes) {
                    String hexStr = ConversionUtils.byteArrayToHexString(receiveBytes);
                    if (StringUtils.isNotBlank(hexStr)) {
                        expectRecvStrs.add(hexStr);
                        if (receiveBytes.length > maxLength) {
                            maxLength = receiveBytes.length;
                        }
                    }
                }
            }
        }
        boolean needSendData = null != sendBytes && sendBytes.length > 0;
        if (!needSendData) {
            //未配置发送包，则连接成功即可
            return new DetectResult(RetStatus.RetSuccess);
        }

        DatagramSocket socket = null;
        try {
            InetAddress inet = InetAddress.getByName(host);

            socket = new DatagramSocket();
            // 两秒接收不到数据认为超时，防止获取不到连接一直在receive阻塞
            socket.setSoTimeout(timeoutMs);
            //发送数据
            DatagramPacket sendPacket = new DatagramPacket(sendBytes, sendBytes.length, inet, port);
            socket.send(sendPacket);
            byte[] recvBuf = new byte[1024];
            DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);
            socket.receive(recvPacket);

            socket.close();
            byte[] recvBytesClone = Arrays.copyOfRange(recvBuf, 0, maxLength);
            String recvHexStr = ConversionUtils.byteArrayToHexString(recvBytesClone);
            if (expectRecvStrs.contains(recvHexStr)) {
                //回包符合预期
                return new DetectResult(RetStatus.RetSuccess);
            }
            return new DetectResult(RetStatus.RetFail);

        } catch (Exception e) {
            LOG.warn("udp detect instance exception, host:{}, port:{}.", instance.getHost(), instance.getPort());
            return new DetectResult(RetStatus.RetFail);
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    @Override
    public Class<? extends Verifier> getPluginConfigClazz() {
        return Config.class;
    }

    @Override
    public String getName() {
        return DefaultValues.DEFAULT_HEALTH_CHECKER_UDP;
    }

    @Override
    public PluginType getType() {
        return PluginTypes.HEALTH_CHECKER.getBaseType();
    }

    @Override
    public void init(InitContext ctx) throws PolarisException {
        OutlierDetectionConfig outlierDetection = ctx.getConfig().getConsumer().getOutlierDetection();
        Config cfg = outlierDetection.getPluginConfig(getName(), Config.class);
        UdpProtocolConfig.Builder builder = UdpProtocolConfig.newBuilder();
        if (null != cfg && StringUtils.isNotBlank(cfg.getSend())) {
            builder.setSend(cfg.getSend());
        }
        if (null != cfg && StringUtils.isNotBlank(cfg.getReceive())) {
            builder.addReceive(cfg.getReceive());
        }
        this.config = builder.build();
    }

    @Override
    public void postContextInit(Extensions extensions) throws PolarisException {

    }

    @Override
    public void destroy() {

    }
}