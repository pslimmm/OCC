import type { BunchingAlert, PendingRecommendation, PlanningDiagnosisRequest, RouteDirection } from '@/lib/api'
import type { RouteIncident } from '@/lib/route-strip'

export type CorridorDiagnosisGroup<TIncident extends RouteIncident = RouteIncident> = {
  id: string
  serviceId: string
  directionId: number | null
  stopCodes: string[]
  stopNames: string[]
  corridorIncident: TIncident | null
  requests: PlanningDiagnosisRequest[]
  representativeRequest: PlanningDiagnosisRequest | null
  firstDetectedAt: string
  latestDetectedAt: string
  gapSeconds: number | null
  isLive: boolean
  status: 'PENDING' | 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED'
}

export function isDiagnosisQueued(request: PlanningDiagnosisRequest) {
  return request.processingRequested && (request.status === 'PENDING' || request.status === 'FAILED_RETRYABLE')
}

function summarizeCorridorDiagnosis(requests: PlanningDiagnosisRequest[]): Pick<CorridorDiagnosisGroup, 'representativeRequest' | 'status'> {
  const processing = requests.find(request => request.status === 'PROCESSING')
  if (processing) return { representativeRequest: processing, status: 'PROCESSING' }
  const queued = requests.find(isDiagnosisQueued)
  if (queued) return { representativeRequest: queued, status: 'QUEUED' }
  const completed = requests.find(request => request.status === 'COMPLETED')
  if (completed) return { representativeRequest: completed, status: 'COMPLETED' }
  const failed = requests.find(request => request.status.startsWith('FAILED'))
  if (failed) return { representativeRequest: failed, status: 'FAILED' }
  return { representativeRequest: requests[0] ?? null, status: 'PENDING' }
}

export function buildCorridorGroups<TIncident extends RouteIncident>(
  requests: PlanningDiagnosisRequest[],
  corridors: TIncident[],
  routes: RouteDirection[]
): CorridorDiagnosisGroup<TIncident>[] {
  const matchedRequestIds = new Set<string>()
  const groups: CorridorDiagnosisGroup<TIncident>[] = []

  // 1. Live corridor incidents from Disruptions feed
  for (const corridor of corridors) {
    const corridorEventIds = new Set(corridor.alerts.map(a => a.eventId))
    const matchingRequests = requests.filter(req => corridorEventIds.has(req.eventId) && !matchedRequestIds.has(req.requestId))

    matchingRequests.forEach(r => matchedRequestIds.add(r.requestId))

    // A detection without a durable Planning request has no actionable identity.
    // Keep it on Disruptions rather than selecting an empty planning detail.
    if (!matchingRequests.length) continue

    groups.push({
      id: corridor.id,
      serviceId: corridor.serviceId,
      directionId: corridor.directionId,
      stopCodes: corridor.stopCodes,
      stopNames: corridor.stopNames,
      corridorIncident: corridor,
      requests: matchingRequests,
      firstDetectedAt: corridor.firstDetectedAt,
      latestDetectedAt: corridor.latestDetectedAt,
      gapSeconds: corridor.gapSeconds,
      isLive: !corridor.historical,
      ...summarizeCorridorDiagnosis(matchingRequests),
    })
  }

  // 2. Remaining requests (historical or non-live sweeps)
  const remaining = requests.filter(r => !matchedRequestIds.has(r.requestId))
  const timeBuckets = new Map<string, PlanningDiagnosisRequest[]>()
  for (const req of remaining) {
    const service = req.serviceId ?? 'unknown'
    const direction = req.directionId ?? 1
    const timeMs = Date.parse(req.createdAt)
    const bucketTime = Number.isFinite(timeMs) ? Math.floor(timeMs / (5 * 60 * 1000)) : 0
    const key = `${service}:${direction}:${bucketTime}`
    const bucket = timeBuckets.get(key) ?? []
    bucket.push(req)
    timeBuckets.set(key, bucket)
  }

  for (const bucket of timeBuckets.values()) {
    const first = bucket[0]
    const service = first.serviceId ?? 'unknown'
    const direction = first.directionId
    const stopCodes = [...new Set(bucket.flatMap(r => r.stopCode ? [r.stopCode] : []))]
    const route = routes.find(r => r.serviceId === service && (direction === null || r.directionId === direction))
    const stopNames = stopCodes.map(code => route?.stops.find(s => s.stopCode === code)?.description || `Stop ${code}`)

    const id = bucket.length === 1 ? bucket[0].requestId : bucket.map(r => r.eventId).sort().join(':') || bucket[0].requestId

    groups.push({
      id,
      serviceId: service,
      directionId: direction,
      stopCodes,
      stopNames,
      corridorIncident: null,
      requests: bucket,
      firstDetectedAt: first.createdAt,
      latestDetectedAt: bucket.at(-1)!.createdAt,
      gapSeconds: null,
      isLive: false,
      ...summarizeCorridorDiagnosis(bucket),
    })
  }

  return groups.sort((a, b) => {
    if (a.isLive !== b.isLive) return a.isLive ? -1 : 1
    return Date.parse(b.latestDetectedAt) - Date.parse(a.latestDetectedAt)
  })
}

export function isActiveRecommendation(recommendation: PendingRecommendation, alerts: BunchingAlert[]): boolean {
  return alerts.some(alert => alert.serviceId === recommendation.serviceId && alert.stopCode === recommendation.monitoredStopCode)
}
