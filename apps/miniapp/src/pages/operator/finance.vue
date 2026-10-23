<script setup>
import {computed,reactive,ref,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {operatorSession,operatorToken,clearOperatorSession} from '../../services/operator-session.js'
import {FINANCE_CHANNELS,FINANCE_CHANNEL_LABELS,currentFinanceDate,initialFinanceState,createLocalFinanceApi,createLocalFinanceFlow,financeView} from '../../services/local-finance.js'

const date=ref(currentFinanceDate()),channel=ref('LOCAL_TEST'),state=reactive(initialFinanceState())
const allowed=()=>operatorSession.canAnalytics
const selection=()=>({date:date.value,channel:channel.value})
const flow=createLocalFinanceFlow({state,token:operatorToken,allowed,selection,api:createLocalFinanceApi()})
const view=computed(()=>state.data?financeView(state.data):null)
const channelLabel=computed(()=>FINANCE_CHANNEL_LABELS[FINANCE_CHANNELS.indexOf(channel.value)])
let visible=false
function changeDate(event){date.value=event.detail.value}
function changeChannel(event){const value=FINANCE_CHANNELS[Number(event.detail.value)];if(value)channel.value=value}
function login(){flow.reset();clearOperatorSession();uni.navigateTo({url:'/pages/operator/login'})}
watch(()=>[date.value,channel.value,operatorSession.accessToken,operatorSession.actorId,allowed()],()=>flow.reset(),{flush:'sync'})
onShow(()=>{visible=true;flow.reset();operatorToken()})
onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
function load(){if(visible)flow.load()}
</script>

<template>
  <view class="reservation-page">
    <view class="ux-hero" data-testid="finance-heading">
      <text class="ux-eyebrow">运营 · 只读查询</text>
      <text class="ux-title">资金记录</text>
      <text class="ux-lead">按北京时间查询系统记录。支付与退款各按成功日统计，本地净额不代表渠道到账或商家结算。</text>
    </view>
    <button v-if="!operatorToken()" class="reservation-primary" @tap="login">运营登录</button>
    <view v-else-if="!allowed()" class="ux-empty" data-testid="finance-forbidden">
      <text class="ux-empty-title">当前账号没有查询权限</text>
      <text class="ux-empty-copy">需要经营数据权限，请联系管理员。</text>
    </view>
    <template v-else>
      <view class="ux-card" data-testid="finance-selection">
        <text class="ux-card-title">选择日期与资金渠道</text>
        <text class="ux-card-note">每次只查询一个渠道，测试资金与正式记录分开显示。</text>
        <picker mode="date" :value="date" :end="currentFinanceDate()" start="2000-01-01" @change="changeDate"><view class="ux-row"><text>北京时间日期：{{date}}</text></view></picker>
        <picker :range="FINANCE_CHANNEL_LABELS" :value="FINANCE_CHANNELS.indexOf(channel)" @change="changeChannel"><view class="ux-row"><text>{{channelLabel}}</text></view></picker>
        <button class="reservation-primary" :disabled="state.busy" :loading="state.busy" data-testid="finance-query" @tap="load">查询所选资金记录</button>
      </view>
      <text v-if="state.busy" role="status">正在核对所选日期的记录…</text>
      <view v-if="state.message" class="ux-card" role="status" data-testid="finance-error">
        <text>{{state.message}}</text>
        <button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录</button>
        <button v-else :disabled="state.busy" @tap="load">重新查询</button>
      </view>
      <template v-if="state.data && view">
        <view class="ux-card" data-testid="finance-results">
          <text class="ux-card-title">{{state.data.date}} · {{view.channel}}</text>
          <text class="ux-card-note">{{view.notice}}</text>
          <view class="ux-row"><text>本地成功支付：{{view.payments}}（{{state.data.payments.success_count}} 笔来源完整记录）</text></view>
          <view class="ux-row"><text>本地成功退款：{{view.refunds}}（{{state.data.refunds.success_count}} 笔来源完整记录）</text></view>
          <view class="ux-row"><text>本地净额：{{view.net}}</text></view>
          <text class="ux-card-note">来源异常或渠道不明：{{view.anomalies}} 笔；未成功记录：{{view.pending}} 笔。</text>
          <text v-if="view.anomalies" class="ux-card-note">存在不完整来源，对应总额显示未知，请由管理员核对原记录。</text>
          <text class="ux-card-note">北京时间：{{view.from}} 起，至 {{view.to}} 之前。</text>
          <text class="ux-card-note">最后核对：{{view.observed}}（北京时间）</text>
        </view>
        <view class="ux-card" data-testid="finance-channel-unknown">
          <text class="ux-card-title">渠道账单尚未接入</text>
          <text class="ux-card-note">渠道净额：未知；对账差额：未知。当前查询不执行支付、退款、结算或账单补写。</text>
        </view>
      </template>
    </template>
    <button @tap="uni.navigateTo({url:'/pages/operator/review'})">运营工作区</button>
  </view>
</template>
