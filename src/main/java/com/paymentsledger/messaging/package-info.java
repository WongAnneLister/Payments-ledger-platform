/**
 * Transactional Outbox、Kafka 发布、Consumer Inbox 与商户通知。
 *
 * <p>首次实现：PR09。PR01 只建立模块包边界，不含业务实现。
 *
 * <p>模块纪律：模块之间通过应用服务接口调用，<b>不跨模块直接操作对方的表</b>；
 * 账本写入一律经 {@code ledger} 模块的唯一过账入口（ADR-0004）。
 */
package com.paymentsledger.messaging;
