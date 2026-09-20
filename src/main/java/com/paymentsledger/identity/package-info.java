/**
 * 身份、组织/应用/Provider 账户范围与受控价格配置。解析可信 TenantContext。
 *
 * <p>PR02 实现认证、范围约束和受控配置只读入口；不含支付或资金写入。
 *
 * <p>模块纪律：模块之间通过应用服务接口调用，<b>不跨模块直接操作对方的表</b>；
 * 账本写入一律经 {@code ledger} 模块的唯一过账入口（ADR-0004）。
 */
package com.paymentsledger.identity;
