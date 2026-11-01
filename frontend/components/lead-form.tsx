"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { Field, Modal } from "./ui";
import { useToast } from "./toast";
import type { LeadDetail } from "@/lib/types";

/**
 * Capture form. Fields map 1:1 onto CreateLeadRequest; the server owns validation
 * (phone → E.164, domain extraction, duplicate candidate detection). Field-level
 * violations returned by the API are attached to the matching input.
 */
export function LeadFormModal({ onClose, onCreated }: {
  onClose: () => void;
  onCreated?: (lead: LeadDetail) => void;
}) {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const [form, setForm] = useState({
    firstName: "", lastName: "", companyName: "", email: "", phone: "", website: "",
    requirement: "", budgetAmount: "", currency: "INR", timelineCode: "", location: "",
    sourceCode: "MANUAL", industryCode: "", consentReference: "",
  });
  const [selectedServices, setSelectedServices] = useState<string[]>([]);
  const [duplicates, setDuplicates] = useState<LeadDetail["duplicates"]>([]);

  const services = useQuery({ queryKey: ["catalog-services"], queryFn: api.services });
  const sources = useQuery({ queryKey: ["catalog-sources"], queryFn: api.sources });

  const mutation = useMutation({
    mutationFn: () =>
      api.createLead(
        {
          firstName: form.firstName.trim(),
          lastName: form.lastName.trim() || null,
          companyName: form.companyName.trim(),
          email: form.email.trim() || null,
          phone: form.phone.trim() || null,
          website: form.website.trim() || null,
          requirement: form.requirement.trim() || null,
          budgetAmount: form.budgetAmount ? Number(form.budgetAmount) : null,
          currency: form.currency,
          timelineCode: form.timelineCode || null,
          location: form.location.trim() || null,
          sourceCode: form.sourceCode || null,
          industryCode: form.industryCode || null,
          consentReference: form.consentReference.trim() || null,
          serviceIds: selectedServices,
        },
        idempotencyKey,
      ),
    onSuccess: (lead) => {
      queryClient.invalidateQueries({ queryKey: ["leads"] });
      queryClient.invalidateQueries({ queryKey: ["dashboard"] });
      if (lead.duplicates.length > 0) {
        setDuplicates(lead.duplicates);
        toast.push(`Lead created. ${lead.duplicates.length} possible duplicate(s) flagged for review.`, "warning");
      } else {
        toast.push("Lead created", "success");
      }
      onCreated?.(lead);
    },
    onError: (error) => {
      toast.push(error instanceof ApiError ? error.message : "Lead could not be saved", "error");
    },
  });

  const error = mutation.error instanceof ApiError ? mutation.error : null;
  const update = (key: keyof typeof form, value: string) =>
    setForm((current) => ({ ...current, [key]: value }));

  return (
    <Modal
      title={duplicates.length > 0 ? "Lead created — possible duplicates" : "Capture lead"}
      onClose={onClose}
      footer={
        duplicates.length > 0 ? (
          <button type="button" className="btn btn--primary" onClick={onClose}>Done</button>
        ) : (
          <>
            <button type="button" className="btn" onClick={onClose}>Cancel</button>
            <button type="submit" form="lead-form" className="btn btn--primary" disabled={mutation.isPending}>
              {mutation.isPending ? "Saving…" : "Create lead"}
            </button>
          </>
        )
      }
    >
      {duplicates.length > 0 ? (
        <div className="stack">
          <p className="small">
            The records below look like the lead you just created. Nothing was merged automatically —
            review each one and merge deliberately from the lead workspace.
          </p>
          <ul style={{ listStyle: "none", padding: 0, margin: 0, display: "flex", flexDirection: "column", gap: 10 }}>
            {duplicates.map((candidate) => (
              <li key={`${candidate.candidateType}-${candidate.candidateId}`} className="card" style={{ padding: 12 }}>
                <div className="row row--between">
                  <strong>{candidate.display}</strong>
                  <span className={`badge badge--${candidate.matchType === "EXACT_EMAIL" ? "danger" : "warning"}`}>
                    {candidate.matchType.replaceAll("_", " ").toLowerCase()} · {Math.round(candidate.confidence * 100)}%
                  </span>
                </div>
                <div className="small muted">{candidate.detail}</div>
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <form id="lead-form" className="stack"
          onSubmit={(event) => { event.preventDefault(); mutation.mutate(); }}>
          <div className="form-grid">
            <Field label="First name *" htmlFor="lf-first" error={error?.fieldError("firstName")}>
              <input id="lf-first" required value={form.firstName}
                aria-invalid={Boolean(error?.fieldError("firstName"))}
                onChange={(e) => update("firstName", e.target.value)} />
            </Field>
            <Field label="Last name" htmlFor="lf-last" error={error?.fieldError("lastName")}>
              <input id="lf-last" value={form.lastName} onChange={(e) => update("lastName", e.target.value)} />
            </Field>
            <Field label="Company *" htmlFor="lf-company" error={error?.fieldError("companyName")}>
              <input id="lf-company" required value={form.companyName}
                onChange={(e) => update("companyName", e.target.value)} />
            </Field>
            <Field label="Work email" htmlFor="lf-email" error={error?.fieldError("email")}
              hint="Exact matches are flagged as strong duplicates.">
              <input id="lf-email" type="email" value={form.email}
                onChange={(e) => update("email", e.target.value)} />
            </Field>
            <Field label="Phone" htmlFor="lf-phone" error={error?.fieldError("phone")}
              hint="Normalised to E.164 by the server.">
              <input id="lf-phone" value={form.phone} onChange={(e) => update("phone", e.target.value)} />
            </Field>
            <Field label="Website" htmlFor="lf-website" error={error?.fieldError("website")}>
              <input id="lf-website" value={form.website} placeholder="acme.com"
                onChange={(e) => update("website", e.target.value)} />
            </Field>
            <Field label="Source" htmlFor="lf-source">
              <select id="lf-source" value={form.sourceCode} onChange={(e) => update("sourceCode", e.target.value)}>
                {(sources.data ?? []).map((source) => (
                  <option key={source.code} value={source.code}>{source.name}</option>
                ))}
                <option value="MANUAL">Manual entry</option>
              </select>
            </Field>
            <Field label="Location" htmlFor="lf-location">
              <input id="lf-location" value={form.location}
                onChange={(e) => update("location", e.target.value)} />
            </Field>
            <Field label="Budget" htmlFor="lf-budget" error={error?.fieldError("budgetAmount")}>
              <input id="lf-budget" type="number" min="0" step="1000" value={form.budgetAmount}
                onChange={(e) => update("budgetAmount", e.target.value)} />
            </Field>
            <Field label="Currency" htmlFor="lf-currency">
              <select id="lf-currency" value={form.currency} onChange={(e) => update("currency", e.target.value)}>
                {["INR", "USD", "EUR", "GBP", "AED", "SGD"].map((code) => (
                  <option key={code} value={code}>{code}</option>
                ))}
              </select>
            </Field>
            <Field label="Timeline" htmlFor="lf-timeline" hint="Drives the timeline scoring factor.">
              <select id="lf-timeline" value={form.timelineCode}
                onChange={(e) => update("timelineCode", e.target.value)}>
                <option value="">Unknown</option>
                <option value="IMMEDIATE">Immediate (≤ 1 month)</option>
                <option value="QUARTER">This quarter</option>
                <option value="HALF_YEAR">Within 6 months</option>
                <option value="EXPLORATORY">Exploratory</option>
              </select>
            </Field>
            <Field label="Consent reference" htmlFor="lf-consent"
              hint="Reference to where consent was captured; required for outbound email.">
              <input id="lf-consent" value={form.consentReference}
                onChange={(e) => update("consentReference", e.target.value)} />
            </Field>
          </div>

          <Field label="Requirement" htmlFor="lf-requirement" error={error?.fieldError("requirement")}>
            <textarea id="lf-requirement" value={form.requirement}
              onChange={(e) => update("requirement", e.target.value)} />
          </Field>

          <fieldset style={{ border: "none", padding: 0, margin: 0 }}>
            <legend className="field__label" style={{ marginBottom: 6 }}>Services of interest</legend>
            <div className="row" style={{ flexWrap: "wrap", gap: 10 }}>
              {(services.data ?? []).filter((service) => service.active).map((service) => (
                <label key={service.id} className="small row" style={{ gap: 6 }}>
                  <input type="checkbox" style={{ width: 16, height: 16 }}
                    checked={selectedServices.includes(service.id)}
                    onChange={(event) =>
                      setSelectedServices((current) =>
                        event.target.checked
                          ? [...current, service.id]
                          : current.filter((id) => id !== service.id))} />
                  {service.name}
                </label>
              ))}
            </div>
          </fieldset>

          {error && !error.fieldErrors.length && (
            <div className="banner banner--danger" role="alert">{error.message}</div>
          )}
        </form>
      )}
    </Modal>
  );
}
