/**
 * API contracts mirroring the Spring Boot DTOs (docs/api/API-SPECIFICATION.md).
 * These types are the only place the UI learns about server payload shapes.
 */

export type ApiEnvelope<T> = {
  success: boolean;
  data?: T;
  error?: ApiErrorBody;
  meta?: { requestId?: string; generatedAt?: string; timezone?: string };
};

export type ApiErrorBody = {
  code: string;
  message: string;
  fieldErrors?: { field: string; code: string; message: string }[];
  requestId?: string;
};

export type PageResponse<T> = {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  sort?: string;
  appliedFilters?: string[];
};

export type SessionInfo = {
  userId: string;
  workspaceId: string;
  displayName: string;
  email: string;
  scopeType: string;
  timezone: string;
  mfaSatisfied: boolean;
  capabilities: string[];
  sessionExpiresAt?: string | null;
};

export type LoginStatus = "AUTHENTICATED" | "MFA_REQUIRED" | "MFA_ENROLMENT_REQUIRED";

export type LoginResponse = {
  status: LoginStatus;
  challengeToken?: string | null;
  enrolmentToken?: string | null;
  profile?: UserProfile | null;
  message: string;
};

export type UserProfile = {
  id: string;
  displayName: string;
  email: string;
  status: string;
  scopeType: string;
  timezone: string;
  mfaEnabled: boolean;
  roles: string[];
  capabilities: string[];
  rowVersion: number;
};

export type LeadStatus =
  | "NEW" | "RESEARCHING" | "QUALIFIED" | "CONTACTED" | "ENGAGED" | "MEETING_SCHEDULED"
  | "DISCOVERY_COMPLETED" | "CONVERTED" | "NURTURE" | "DISQUALIFIED" | "REOPENED" | "ARCHIVED";

export type DuplicateCandidate = {
  candidateType: "LEAD" | "CONTACT" | "ACCOUNT";
  candidateId: string;
  matchType: string;
  confidence: number;
  display: string;
  detail: string;
};

export type ScoreFactor = {
  code: string;
  weight: number;
  fit: number;
  points: number;
  evidence: string;
};

export type ScoreView = {
  total: number;
  band: string;
  modelVersion: number;
  factors: ScoreFactor[];
  calculatedAt: string;
};

export type LeadSummary = {
  id: string;
  fullName: string;
  companyName: string;
  email: string | null;
  phone: string | null;
  status: LeadStatus;
  score: number | null;
  scoreBand: string | null;
  ownerName: string | null;
  ownerUserId: string | null;
  sourceName: string | null;
  primaryServiceName: string | null;
  updatedAt: string;
  rowVersion: number;
};

export type LeadDetail = {
  id: string;
  firstName: string;
  lastName: string | null;
  email: string | null;
  phone: string | null;
  whatsapp: string | null;
  companyName: string;
  website: string | null;
  domain: string | null;
  industryCode: string | null;
  serviceIds: string[];
  sourceCode: string | null;
  sourceEvidenceRef: string | null;
  requirement: string | null;
  budgetAmount: number | null;
  currency: string | null;
  timelineCode: string | null;
  location: string | null;
  employeeBand: string | null;
  status: LeadStatus;
  score: number | null;
  scoreBand: string | null;
  latestScore: ScoreView | null;
  ownerUserId: string | null;
  ownerName: string | null;
  consentReference: string | null;
  allowedTransitions: LeadStatus[];
  duplicates: DuplicateCandidate[];
  createdAt: string;
  updatedAt: string;
  rowVersion: number;
};

export type AccountSummary = {
  id: string;
  name: string;
  domain: string | null;
  industryCode: string | null;
  sizeBand: string | null;
  relationshipStatus: string;
  ownerName: string | null;
  contactCount: number;
  openOpportunityCount: number;
  updatedAt: string;
  rowVersion: number;
};

export type ContactSummary = {
  id: string;
  fullName: string;
  title: string | null;
  email: string | null;
  phone: string | null;
  decisionRole: string | null;
  consentStatus: string;
  suppressionStatus: string;
  accountId: string | null;
  accountName: string | null;
  updatedAt: string;
  rowVersion: number;
};

export type AccountDetail = {
  id: string;
  name: string;
  legalName: string | null;
  website: string | null;
  domain: string | null;
  industryCode: string | null;
  sizeBand: string | null;
  headquarters: string | null;
  description: string | null;
  relationshipStatus: string;
  ownerUserId: string | null;
  ownerName: string | null;
  contacts: ContactSummary[];
  createdAt: string;
  updatedAt: string;
  rowVersion: number;
};

export type StageRef = { id: string; code: string; name: string; order: number; terminalType: string };

export type OpportunitySummary = {
  id: string;
  name: string;
  accountName: string | null;
  accountId: string;
  stage: StageRef | null;
  amount: number;
  currency: string;
  weightedValue: number;
  probability: number;
  expectedCloseDate: string | null;
  closeDate: string | null;
  ownerName: string | null;
  updatedAt: string;
  rowVersion: number;
};

export type StageHistoryEntry = {
  fromStage: string | null;
  toStage: string | null;
  actorName: string;
  reason: string | null;
  ruleVersion: string;
  occurredAt: string;
};

export type OpportunityDetail = OpportunitySummary & {
  primaryContactId: string | null;
  primaryContactName: string | null;
  leadId: string | null;
  serviceId: string | null;
  serviceName: string | null;
  finalAmount: number | null;
  finalCurrency: string | null;
  lossReasonCode: string | null;
  lossNotes: string | null;
  competitor: string | null;
  nextStep: string | null;
  ownerUserId: string | null;
  decisionContactId: string | null;
  availableStages: StageRef[];
  history: StageHistoryEntry[];
  createdAt: string;
};

export type PipelineColumn = {
  stageCode: string;
  stageName: string;
  order: number;
  terminalType: string;
  totalAmount: number;
  currency: string;
  count: number;
  opportunities: OpportunitySummary[];
};

export type PipelineBoard = {
  columns: PipelineColumn[];
  generatedAt: string;
  limitations: string[];
};

export type CurrencyPipeline = {
  currency: string;
  openAmount: number;
  weightedAmount: number;
  count: number;
};

export type Kpi = {
  code: string;
  label: string;
  value: string;
  unit: string;
  definition: string;
  currency: string | null;
  trend: string | null;
};

export type TaskView = {
  id: string;
  title: string;
  description: string | null;
  dueAt: string | null;
  status: string;
  priority: string;
  relatedType: string | null;
  relatedId: string | null;
  ownerName: string | null;
  overdue: boolean;
  completedAt: string | null;
  rowVersion: number;
};

export type ActivityView = {
  id: string;
  type: string;
  relatedType: string;
  relatedId: string;
  actorName: string | null;
  subject: string | null;
  body: string | null;
  occurredAt: string;
};

export type CurrencyTotal = { currency: string; amount: number; count: number };

export type ForecastView = {
  periodStart: string;
  periodEnd: string;
  openByCurrency: CurrencyTotal[];
  weightedByCurrency: CurrencyTotal[];
  wonByCurrency: CurrencyTotal[];
  wonCount: number;
  lostCount: number;
  winRatePercent: number;
  averageWonValue: number;
  wonCurrency: string | null;
  definitions: string[];
  generatedAt: string;
};

export type DashboardView = {
  periodStart: string;
  periodEnd: string;
  timezone: string;
  forecast: ForecastView;
  openPipeline: CurrencyTotal[];
  weightedPipeline: CurrencyTotal[];
  wonValue: CurrencyTotal[];
  definitions: string[];
  limitations: string[];
  generatedAt: string;
};

export type FeedItem = {
  id: string;
  type: string;
  relatedType: string;
  relatedId: string;
  subject: string | null;
  actorName: string | null;
  occurredAt: string;
};

export type UserSummary = {
  id: string;
  displayName: string;
  email: string;
  status: string;
  scopeType: string;
  mfaEnabled: boolean;
  roles: string[];
  lastLoginAt: string | null;
  rowVersion: number;
};

export type RoleSummary = {
  code: string;
  name: string;
  description: string;
  capabilities: string[];
};

export type AuditView = {
  id: string;
  actorType: string;
  actorUserId: string | null;
  action: string;
  objectType: string;
  objectId: string | null;
  outcome: string;
  correlationId: string | null;
  occurredAt: string;
  recordHash: string;
  prevHash: string | null;
};

export type ServiceView = {
  id: string;
  code: string;
  name: string;
  division: string;
  description: string | null;
  active: boolean;
  claimsApproved: boolean;
  rowVersion: number;
};

export type StageView = {
  id: string;
  code: string;
  name: string;
  orderNo: number;
  terminalType: string;
  allowSkip: boolean;
  active: boolean;
  rowVersion: number;
};

export type SimpleRef = { id: string; code: string; name: string };

export type NotificationView = {
  id: string;
  type: string;
  severity: string;
  title: string;
  body: string | null;
  link: string | null;
  readAt: string | null;
  createdAt: string;
};

export type SearchHit = {
  type: string;
  id: string;
  title: string;
  subtitle: string;
  link: string;
};

export type JobView = {
  id: string;
  jobType: string;
  status: string;
  attemptCount: number;
  maxAttempts: number;
  lastError: string | null;
  availableAt: string | null;
  completedAt: string | null;
};

// --- Engagement: email -----------------------------------------------------

export type EmailStatus =
  | "DRAFT" | "PENDING_APPROVAL" | "SCHEDULED" | "QUEUED" | "SENDING"
  | "SENT" | "DELIVERED" | "BOUNCED" | "REPLIED" | "CANCELLED";

export type EmailView = {
  id: string;
  status: EmailStatus;
  subject: string;
  toEmail: string;
  toName: string | null;
  leadId: string | null;
  opportunityId: string | null;
  smtpProfileName: string | null;
  scheduledAt: string | null;
  sentAt: string | null;
  sentByName: string | null;
  approvedAt: string | null;
  failureCode: string | null;
  failureDetail: string | null;
  attemptCount: number;
  createdAt: string;
  rowVersion: number;
};

export type EmailEvent = { eventType: string; detail: string | null; occurredAt: string };

export type EligibilityView = {
  allowed: boolean;
  blockers: string[];
  warnings: string[];
  consentStatus: string | null;
};

export type EmailDetail = {
  message: EmailView;
  bodyHtml: string | null;
  bodyText: string | null;
  events: EmailEvent[];
  eligibility: EligibilityView;
};

export type DraftResult = { id: string; status: string; message: string; warnings: string[] };
export type SendResult = {
  id: string; status: string; sentAt: string | null; providerMessageId: string | null; detail: string | null;
};

export type TemplateView = {
  id: string;
  name: string;
  purpose: string | null;
  subject: string;
  body: string;
  mergeFields: string[];
  status: string;
  version: number;
  approvedAt: string | null;
  updatedAt: string;
  rowVersion: number;
};

export type TemplatePreview = {
  subject: string;
  body: string;
  mergeFields: string[];
  sampleValues: string[];
};

export type SmtpProfileView = {
  id: string;
  name: string;
  fromName: string;
  fromEmail: string;
  replyToEmail: string | null;
  host: string;
  port: number;
  tlsMode: string;
  username: string;
  dailyLimit: number;
  hourlyLimit: number;
  status: string;
  lastTestedAt: string | null;
  lastTestDetail: string | null;
  sentToday: number;
  rowVersion: number;
};

export type SmtpTestResult = {
  delivered: boolean;
  detail: string;
  testedAt: string | null;
  host: string;
  port: number;
  tlsMode: string;
};

// --- Engagement: sequences -------------------------------------------------

export type SequenceStepView = {
  stepNo: number;
  dayOffset: number;
  templateName: string;
  subjectOverride: string | null;
  stopOnReply: boolean;
};

export type SequenceView = {
  id: string;
  name: string;
  description: string | null;
  status: string;
  activeVersion: number | null;
  stepCount: number;
  steps: SequenceStepView[];
  updatedAt: string;
  rowVersion: number;
};

export type EnrollmentView = {
  id: string;
  sequenceId: string;
  sequenceName: string;
  leadName: string;
  status: string;
  currentStep: number;
  totalSteps: number;
  nextRunAt: string | null;
  enrolledAt: string;
  stopReason: string | null;
};

// ---------------------------------------------------------------- two-way mailbox (addendum)

export type MailboxSyncState = {
  uidValidity: number | null;
  lastUid: number;
  lastSyncAt: string | null;
  lastSuccessAt: string | null;
  consecutiveFailures: number;
  lastError: string | null;
  reconciliationStatus: string;
};

export type MailboxAccountView = {
  id: string;
  name: string;
  provider: "IMAP" | "GMAIL_API" | "MICROSOFT_GRAPH";
  mailboxAddress: string;
  fromName: string | null;
  inboundEnabled: boolean;
  outboundEnabled: boolean;
  smtpProfileName: string | null;
  host: string | null;
  port: number | null;
  tlsMode: string | null;
  folder: string | null;
  authMethod: string;
  username: string | null;
  status: "PENDING_TEST" | "ACTIVE" | "DISABLED" | "ERROR";
  pollIntervalSeconds: number;
  lastHealthAt: string | null;
  lastError: string | null;
  hasSecret: boolean;
  hasOauthToken: boolean;
  ownerName: string | null;
  queueKey: string | null;
  sync: MailboxSyncState | null;
  rowVersion: number;
};

export type MailboxTestResult = {
  ok: boolean;
  detail: string;
  testedAt: string;
  host: string | null;
  port: number | null;
  tlsMode: string | null;
  folder: string | null;
  messageCount: number | null;
};

export type MailboxSyncResult = {
  accountId: string | null;
  mailbox: string;
  fetched: number;
  persisted: number;
  duplicates: number;
  failed: number;
  detail: string;
  completedAt: string;
};

export type InboxAttachment = {
  id: string;
  filename: string;
  mediaType: string | null;
  sizeBytes: number;
  scanStatus: string;
};

export type InboxMessageView = {
  id: string;
  direction: "INBOUND" | "OUTBOUND";
  fromEmail: string | null;
  fromName: string | null;
  toEmail: string | null;
  subject: string | null;
  bodyText: string | null;
  bodyHtml: string | null;
  receivedAt: string | null;
  sentAt: string | null;
  status: string;
  messageIdHeader: string | null;
  inReplyToHeader: string | null;
  aiStatus: string | null;
  attachments: InboxAttachment[];
};

export type InboxAnalysis = {
  intent: string;
  subIntent: string | null;
  sentiment: string | null;
  urgency: string | null;
  buyingSignal: string | null;
  confidence: number;
  recommendedAction: string | null;
  replyDraft: string | null;
  provider: string;
  model: string;
  promptVersion: string;
  validated: boolean;
  createdAt: string | null;
  extracted: Record<string, unknown>[];
  questions: string[];
  risks: string[];
};

export type EmailApprovalView = {
  id: string;
  messageId: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED";
  reasonCode: string | null;
  requestedByName: string;
  decidedByName: string | null;
  decidedAt: string | null;
  rejectionReason: string | null;
  expiresAt: string | null;
  createdAt: string | null;
};

export type MailboxPolicyView = {
  mode: "AUTOMATIC" | "APPROVAL_REQUIRED" | "HUMAN" | "NO_RESPONSE";
  reasonCode: string;
  ruleName: string | null;
  mayAutoSend: boolean;
  delaySeconds: number;
};

export type InboxConversation = {
  id: string;
  subject: string | null;
  mailboxAddress: string;
  accountId: string | null;
  leadId: string | null;
  leadName: string | null;
  contactId: string | null;
  contactName: string | null;
  assignedUserId: string | null;
  assignedUserName: string | null;
  queueKey: string | null;
  status: string;
  priority: string;
  unread: boolean;
  messageCount: number;
  lastMessageAt: string | null;
  matchReason: string;
  awaitingApproval: boolean;
};

export type InboxConversationDetail = {
  summary: InboxConversation;
  messages: InboxMessageView[];
  analysis: InboxAnalysis | null;
  approval: EmailApprovalView | null;
  policy: MailboxPolicyView | null;
  aiDraft: string | null;
  rowVersion: number;
};

export type InboxStats = {
  totalOpen: number;
  unread: number;
  unassigned: number;
  awaitingApproval: number;
  analysedToday: number;
};

export type MailboxRuleView = {
  id: string;
  name: string;
  priority: number;
  enabled: boolean;
  mode: "AUTOMATIC" | "APPROVAL_REQUIRED" | "HUMAN" | "NO_RESPONSE";
  allowedIntents: string[];
  blockedIntents: string[];
  minConfidence: number;
  businessDays: string[];
  businessHoursStart: string | null;
  businessHoursEnd: string | null;
  delaySeconds: number;
  createdAt: string | null;
  rowVersion: number;
};
