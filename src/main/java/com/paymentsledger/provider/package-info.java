/**
 * Provider 端口与适配器。Stripe SDK 类型<b>只允许</b>出现在本模块的 adapter 内部。
 *
 * <p>首次实现：PR04（端口与 Fake）、PR05（真实 Stripe 适配器）。PR01 只建立模块包边界，不含业务实现。
 *
 * <p>模块纪律：模块之间通过应用服务接口调用，<b>不跨模块直接操作对方的表</b>；
 * 账本写入一律经 {@code ledger} 模块的唯一过账入口（ADR-0004）。
 */
package com.paymentsledger.provider;
