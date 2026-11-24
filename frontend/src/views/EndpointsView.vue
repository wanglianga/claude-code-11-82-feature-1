<template>
  <div>
    <h2 class="page-title">临床表型端点</h2>
    <p class="page-desc">
      把首次确诊、治疗起点与影像检查组合成稳定表型端点：区分事件发生、记录可见与确认完成三时点；
      严格锚点窗口稳定但丢失延迟检查，优先级匹配覆盖更多且公开全部候选；补录不倒写已发表端点，重分类超门限输出桥接报告。
    </p>

    <!-- 定义册 -->
    <div class="card">
      <h3>端点定义册（预登记：窗口 / 匹配策略 / 冲突裁定 / 重分类门限）</h3>
      <table>
        <thead><tr><th>编码</th><th>名称</th><th>组件与窗口</th><th>冲突裁定优先级</th><th>门限</th><th>状态</th><th>specHash</th></tr></thead>
        <tbody>
          <tr v-for="d in definitions" :key="d.id">
            <td class="mono">{{ d.code }}</td>
            <td>{{ d.name }}</td>
            <td class="small">
              <div v-for="c in d.definition.components" :key="c.code">
                <b>{{ c.code }}</b>（{{ c.type }}<template v-if="c.modality">/{{ c.modality }}</template>，{{ c.matchStrategy }}）
                <span v-if="c.window">：{{ c.window.label }} 参{{ c.window.ref }} [−{{ c.window.beforeDays }},+{{ c.window.afterDays }}]天</span>
                <span v-for="w in c.windows" :key="w.label">；{{ w.label }} 参{{ w.ref }} [−{{ w.beforeDays }},+{{ w.afterDays }}]天</span>
              </div>
            </td>
            <td class="small mono">{{ (d.definition.conflictPolicy?.IMAGING || []).join(' &gt; ') }}</td>
            <td>{{ (d.definition.reclassificationThreshold * 100).toFixed(0) }}%</td>
            <td><span class="badge" :class="d.status === 'REGISTERED' ? 'ok' : 'gray'">{{ d.status }}</span></td>
            <td class="mono small">{{ d.specHash.slice(0, 14) }}…</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 试算 -->
    <div class="card mt">
      <h3>证据时间图试算（不冻结、不落账）</h3>
      <div class="form-row">
        <div>
          <label>端点定义</label>
          <select v-model="trial.definitionId">
            <option v-for="d in definitions" :key="d.id" :value="d.id">{{ d.code }} · {{ d.name }}</option>
          </select>
        </div>
        <div>
          <label>数据水线（可见性/确认闸门）</label>
          <select v-model="trial.waterlineLabel">
            <option value="WL-2026Q3">WL-2026Q3（2026-09-30）</option>
            <option value="WL-2026Q4">WL-2026Q4（2026-11-20）</option>
          </select>
        </div>
        <div style="flex: 0">
          <button class="btn-primary" :disabled="!trial.definitionId" @click="runTrial">运行试算</button>
        </div>
        <div style="flex: 0">
          <button class="btn-ghost" :disabled="!trialResult" @click="publish">冻结为发表</button>
        </div>
      </div>
    </div>

    <div v-if="trialResult" class="card mt">
      <h3>试算结果 · {{ trialResult.definitionCode }} @ {{ trialResult.waterline.label }}</h3>
      <div class="grid cols-3">
        <div><div class="kpi">{{ trialResult.summary.totalPatients }}</div><div class="kpi-label">证据涉及患者</div></div>
        <div><div class="kpi">{{ trialResult.summary.eligiblePatients }}</div><div class="kpi-label">合格（有确诊锚点）</div></div>
        <div><div class="kpi">{{ trialResult.summary.includedPatients }}</div><div class="kpi-label">端点纳入</div></div>
        <div><div class="kpi" style="color: var(--warn)">{{ trialResult.summary.inclusionLoss }}</div><div class="kpi-label">纳入损失（{{ (trialResult.summary.inclusionLossRate * 100).toFixed(1) }}%）</div></div>
        <div><div class="kpi">{{ trialResult.summary.delayedCheckLoss }}</div><div class="kpi-label">延迟检查出窗损失</div></div>
        <div><div class="kpi">{{ trialResult.summary.unconfirmedBlocked }}</div><div class="kpi-label">未确认拦截</div></div>
      </div>
      <p class="small mt" v-if="trialResult.summary.backfillEvidenceHiddenAtCutoff">
        水线处尚有 {{ trialResult.summary.backfillEvidenceHiddenAtCutoff }} 条补录证据不可见（NOT_VISIBLE_YET），不参与本次端点。
      </p>

      <h3 class="mt">逐患者表型与纳入损失</h3>
      <table>
        <thead><tr><th>患者</th><th>表型/原因</th><th>命中组件与窗口</th><th>候选公开</th><th>时间图</th></tr></thead>
        <tbody>
          <tr v-for="p in trialResult.patients" :key="p.patientKey">
            <td class="mono">{{ p.patientKey }}</td>
            <td>
              <span v-if="p.included" class="badge ok">纳入 · {{ p.phenotype }}</span>
              <span v-else class="badge" :class="p.eligible ? 'warn' : 'bad'">{{ p.exclusionReason }}</span>
            </td>
            <td class="small">
              <div v-for="s in p.selected" :key="s.component">
                {{ s.component }} → <b>{{ s.eventKey }}</b>（{{ s.windowLabel }}，{{ s.gapDays }} 天，{{ s.matchStrategy }}）
              </div>
            </td>
            <td class="small">
              <span v-for="(c, i) in p.candidates" :key="i" class="mono" style="margin-right: 8px">
                <span :class="c.inWindow ? '' : 'tag-suppressed'">{{ c.eventKey }}@{{ c.windowLabel }}({{ c.gapDays }}d{{ c.inWindow ? '' : ',窗外' }})</span>
              </span>
            </td>
            <td><button class="btn-ghost btn-sm" @click="showTimeline(p)">三时点</button></td>
          </tr>
        </tbody>
      </table>

      <h3 class="mt" v-if="trialResult.arbitrations.length">双窗口冲突预登记裁定（{{ trialResult.arbitrations.length }} 条）</h3>
      <table v-if="trialResult.arbitrations.length">
        <thead><tr><th>患者</th><th>检查</th><th>采纳窗口</th><th>驳回窗口</th><th>依据</th></tr></thead>
        <tbody>
          <tr v-for="(a, i) in trialResult.arbitrations" :key="i">
            <td class="mono">{{ a.patientKey }}</td>
            <td class="mono">{{ a.eventKey }}</td>
            <td><span class="badge ok">{{ a.acceptedWindow }}</span></td>
            <td><span class="badge warn">{{ a.rejectedWindow }}</span></td>
            <td class="small">{{ a.basis }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="timelinePatient" class="card mt">
      <h3>{{ timelinePatient.patientKey }} 证据时间图（发生 / 记录可见 / 确认完成）</h3>
      <table>
        <thead><tr><th>事件</th><th>类型</th><th>发生</th><th>记录可见</th><th>确认完成</th><th>水线处状态</th><th>补录</th></tr></thead>
        <tbody>
          <tr v-for="t in timelinePatient.timeline" :key="t.eventKey">
            <td class="mono">{{ t.eventKey }}</td>
            <td>{{ t.type }}<template v-if="t.modality">/{{ t.modality }}</template></td>
            <td class="small">{{ fmt(t.occurredAt) }}</td>
            <td class="small">{{ fmt(t.recordedAt) }}</td>
            <td class="small">{{ t.confirmedAt ? fmt(t.confirmedAt) : '未确认' }}</td>
            <td><span class="badge" :class="visibilityClass(t.visibility)">{{ t.visibility }}</span></td>
            <td><span v-if="t.backfill" class="badge warn">补录</span><span v-else class="badge gray">原始</span></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 发表与桥接 -->
    <div class="card mt">
      <h3>冻结发表与桥接报告</h3>
      <table>
        <thead><tr><th>发表编号</th><th>定义</th><th>水线</th><th>状态</th><th>载荷 sha256</th><th>桥接（新水线）</th></tr></thead>
        <tbody>
          <tr v-for="p in publications" :key="p.id">
            <td class="mono">{{ p.code }}</td>
            <td class="mono">{{ p.definitionCode }}</td>
            <td>{{ p.waterlineLabel }}</td>
            <td><span class="badge" :class="p.status === 'FROZEN' ? 'ok' : 'gray'">{{ p.status }}</span></td>
            <td class="mono small">{{ p.payloadSha256.slice(0, 14) }}…</td>
            <td>
              <button class="btn-primary btn-sm" @click="bridge(p, 'WL-2026Q4')">用 WL-2026Q4 桥接</button>
            </td>
          </tr>
          <tr v-if="!publications.length"><td colspan="6" class="muted">暂无发表，试算后可冻结</td></tr>
        </tbody>
      </table>
    </div>

    <div v-if="bridgeResult" class="card mt">
      <h3>桥接报告 {{ bridgeResult.code }}
        <span class="badge" :class="bridgeResult.report.decision === 'BRIDGE_REQUIRED' ? 'bad' : 'ok'">
          {{ bridgeResult.report.decision }}
        </span>
      </h3>
      <p class="small">{{ bridgeResult.report.decisionRule }}</p>
      <div class="grid cols-3">
        <div><div class="kpi">{{ bridgeResult.report.reclassifiedCount }}</div><div class="kpi-label">重分类患者 / 并集 {{ bridgeResult.report.unionPatientCount }}</div></div>
        <div><div class="kpi">{{ (bridgeResult.report.reclassificationRate * 100).toFixed(1) }}%</div><div class="kpi-label">重分类率（门限 {{ (bridgeResult.report.reclassificationThreshold * 100).toFixed(0) }}%）</div></div>
        <div><div class="kpi mono" style="font-size: 13px">{{ bridgeResult.reportSha256.slice(0, 18) }}…</div><div class="kpi-label">报告 sha256</div></div>
      </div>
      <table class="mt">
        <thead><tr><th>患者</th><th>旧发表状态</th><th>新水线状态</th><th>类别</th></tr></thead>
        <tbody>
          <tr v-for="(r, i) in bridgeResult.report.reclassifications" :key="i">
            <td class="mono">{{ r.patientKey }}</td>
            <td class="small">{{ r.oldState }}</td>
            <td class="small">{{ r.newState }}</td>
            <td><span class="badge info">{{ r.category }}</span></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 证据受理 -->
    <div class="card mt">
      <h3>证据事件受理（三时点留痕 / 补录不倒写）</h3>
      <div class="form-row">
        <div><label>患者键</label><input v-model="intake.patientKey" placeholder="P007" /></div>
        <div><label>事件键</label><input v-model="intake.eventKey" placeholder="EVT-NEW-1" /></div>
        <div>
          <label>类型</label>
          <select v-model="intake.eventType">
            <option>DIAGNOSIS</option><option>TREATMENT</option><option>IMAGING</option>
          </select>
        </div>
        <div><label>发生日期</label><input type="date" v-model="intake.occurredDate" /></div>
        <div style="flex: 0; display: flex; gap: 8px">
          <button class="btn-ghost btn-sm" @click="intakeEvent(false)">受理事件</button>
          <button class="btn-primary btn-sm" @click="intakeEvent(true)">受理补录</button>
        </div>
      </div>
      <table class="mt">
        <thead><tr><th>事件</th><th>患者</th><th>类型</th><th>发生</th><th>可见</th><th>确认</th><th>补录</th><th></th></tr></thead>
        <tbody>
          <tr v-for="e in events.slice().reverse().slice(0, 12)" :key="e.id">
            <td class="mono small">{{ e.eventKey }}</td>
            <td class="mono">{{ e.patientKey }}</td>
            <td>{{ e.eventType }}</td>
            <td class="small">{{ fmt(e.occurredAt) }}</td>
            <td class="small">{{ fmt(e.recordedAt) }}</td>
            <td class="small">{{ e.confirmedAt ? fmt(e.confirmedAt) : '—' }}</td>
            <td><span v-if="e.backfill" class="badge warn">补录</span><span v-else class="badge gray">原始</span></td>
            <td><button v-if="!e.confirmedAt" class="btn-ghost btn-sm" @click="confirm(e)">确认完成</button></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import api from '../api'

const definitions = ref([])
const publications = ref([])
const events = ref([])
const trial = reactive({ definitionId: null, waterlineLabel: 'WL-2026Q3' })
const trialResult = ref(null)
const timelinePatient = ref(null)
const bridgeResult = ref(null)
const intake = reactive({ patientKey: 'P007', eventKey: '', eventType: 'IMAGING', occurredDate: '2026-08-01' })

function fmt(ms) { return ms ? new Date(ms).toLocaleDateString() : '' }
function dayMs(text) { return Date.parse(text + 'T00:00:00Z') }
function visibilityClass(v) {
  if (v === 'VISIBLE_CONFIRMED') return 'ok'
  if (v === 'VISIBLE_UNCONFIRMED') return 'warn'
  return 'bad'
}

async function loadAll() {
  const [defs, pubs, evts] = await Promise.all([
    api.get('/endpoints/definitions'),
    api.get('/endpoints/publications'),
    api.get('/endpoints/events')
  ])
  definitions.value = defs.data
  publications.value = pubs.data
  events.value = evts.data
  if (!trial.definitionId && defs.data.length) trial.definitionId = defs.data[0].id
}
async function runTrial() {
  const { data } = await api.get('/endpoints/trial', {
    params: { definitionId: trial.definitionId, waterlineLabel: trial.waterlineLabel }
  })
  trialResult.value = data
  timelinePatient.value = null
}
function showTimeline(p) { timelinePatient.value = p }
async function publish() {
  const { data } = await api.post('/endpoints/publications', {
    definitionId: trial.definitionId, waterlineLabel: trial.waterlineLabel
  })
  publications.value.unshift(data)
  await loadAll()
}
async function bridge(p, newWaterlineLabel) {
  const { data } = await api.post(`/endpoints/publications/${p.id}/bridge`, { newWaterlineLabel })
  bridgeResult.value = data
}
async function intakeEvent(backfill) {
  const body = {
    patientKey: intake.patientKey,
    eventKey: intake.eventKey || ('EVT-UI-' + Date.now()),
    eventType: intake.eventType,
    occurredAt: dayMs(intake.occurredDate),
    modality: intake.eventType === 'IMAGING' ? 'CT' : null,
    bodyPart: intake.eventType === 'IMAGING' ? 'CHEST' : null
  }
  await api.post(backfill ? '/endpoints/events/backfill' : '/endpoints/events', body)
  await loadAll()
}
async function confirm(e) {
  await api.post(`/endpoints/events/${e.id}/confirm`)
  await loadAll()
}
onMounted(loadAll)
</script>
