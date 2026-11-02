"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { titleCase } from "@/lib/format";
import { Card, ErrorState, Field, Modal, PageHeader, TableSkeleton } from "@/components/ui";
import { useToast } from "@/components/toast";
import type { ServiceView, StageView } from "@/lib/types";

export default function CatalogPage() {
  const queryClient = useQueryClient();
  const toast = useToast();
  const [editService, setEditService] = useState<ServiceView | null>(null);
  const [editStage, setEditStage] = useState<StageView | null>(null);
  const [name, setName] = useState("");
  const [division, setDivision] = useState("");
  const [description, setDescription] = useState("");
  const [active, setActive] = useState(true);
  const [allowSkip, setAllowSkip] = useState(false);

  const services = useQuery({ queryKey: ["catalog-services"], queryFn: api.services });
  const stages = useQuery({ queryKey: ["catalog-stages"], queryFn: api.stages });
  const sources = useQuery({ queryKey: ["catalog-sources"], queryFn: api.sources });
  const industries = useQuery({ queryKey: ["catalog-industries"], queryFn: api.industries });
  const lossReasons = useQuery({ queryKey: ["loss-reasons"], queryFn: api.lossReasons });

  const saveService = useMutation({
    mutationFn: () => api.updateService(editService!.id, { name, division, description, active }),
    onSuccess: () => {
      toast.push("Service updated", "success");
      setEditService(null);
      queryClient.invalidateQueries({ queryKey: ["catalog-services"] });
    },
    onError: (error) => toast.push(error instanceof ApiError ? error.message : "Update failed", "error"),
  });

  const saveStage = useMutation({
    mutationFn: () => api.updateStage(editStage!.id, { name, allowSkip, active }),
    onSuccess: () => {
      toast.push("Stage updated", "success");
      setEditStage(null);
      queryClient.invalidateQueries({ queryKey: ["catalog-stages"] });
      queryClient.invalidateQueries({ queryKey: ["pipeline"] });
    },
    onError: (error) => toast.push(error instanceof ApiError ? error.message : "Update failed", "error"),
  });

  return (
    <>
      <PageHeader title="Catalogues"
        subtitle="Controlled vocabularies used by scoring, pipeline gates and reporting." />

      <div className="stack">
        <Card title="Service offerings" padded={false}>
          {services.isPending && <TableSkeleton rows={4} />}
          {services.isError && <ErrorState message={(services.error as Error).message} onRetry={() => services.refetch()} />}
          {services.data && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">Code</th>
                    <th scope="col">Name</th>
                    <th scope="col">Division</th>
                    <th scope="col">Claims approved</th>
                    <th scope="col">Status</th>
                    <th scope="col" />
                  </tr>
                </thead>
                <tbody>
                  {services.data.map((service) => (
                    <tr key={service.id}>
                      <td className="mono">{service.code}</td>
                      <td><strong>{service.name}</strong>
                        {service.description && <div className="small muted">{service.description}</div>}
                      </td>
                      <td className="small">{service.division}</td>
                      <td>
                        <span className={`badge badge--${service.claimsApproved ? "success" : "warning"}`}>
                          {service.claimsApproved ? "Approved" : "Review required"}
                        </span>
                      </td>
                      <td><span className={`badge badge--${service.active ? "success" : "neutral"}`}>
                        {service.active ? "Active" : "Inactive"}
                      </span></td>
                      <td style={{ textAlign: "right" }}>
                        <button type="button" className="btn btn--sm" onClick={() => {
                          setEditService(service);
                          setName(service.name);
                          setDivision(service.division);
                          setDescription(service.description ?? "");
                          setActive(service.active);
                        }}>Edit</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <Card title="Pipeline stages" padded={false}>
          {stages.isPending && <TableSkeleton rows={5} />}
          {stages.isError && <ErrorState message={(stages.error as Error).message} onRetry={() => stages.refetch()} />}
          {stages.data && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th scope="col">#</th>
                    <th scope="col">Stage</th>
                    <th scope="col">Terminal</th>
                    <th scope="col">Skip allowed</th>
                    <th scope="col">Status</th>
                    <th scope="col" />
                  </tr>
                </thead>
                <tbody>
                  {stages.data.map((stage) => (
                    <tr key={stage.id}>
                      <td>{stage.orderNo}</td>
                      <td><strong>{stage.name}</strong><div className="small muted mono">{stage.code}</div></td>
                      <td className="small">{stage.terminalType === "NONE" ? "—" : titleCase(stage.terminalType)}</td>
                      <td className="small">{stage.allowSkip ? "Yes (with evidence)" : "No"}</td>
                      <td><span className={`badge badge--${stage.active ? "success" : "neutral"}`}>
                        {stage.active ? "Active" : "Inactive"}
                      </span></td>
                      <td style={{ textAlign: "right" }}>
                        <button type="button" className="btn btn--sm" onClick={() => {
                          setEditStage(stage);
                          setName(stage.name);
                          setAllowSkip(stage.allowSkip);
                          setActive(stage.active);
                        }}>Edit</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <div className="grid grid--2">
          <Card title="Lead sources">
            <div className="row" style={{ flexWrap: "wrap", gap: 6 }}>
              {(sources.data ?? []).map((source) => (
                <span key={source.id} className="badge badge--neutral">{source.name}</span>
              ))}
            </div>
          </Card>
          <Card title="Loss reasons">
            <div className="row" style={{ flexWrap: "wrap", gap: 6 }}>
              {(lossReasons.data ?? []).map((reason) => (
                <span key={reason.id} className="badge badge--neutral">{reason.name}</span>
              ))}
            </div>
          </Card>
          <Card title="Industries">
            <div className="row" style={{ flexWrap: "wrap", gap: 6 }}>
              {(industries.data ?? []).map((industry) => (
                <span key={industry.id} className="badge badge--neutral">{industry.name}</span>
              ))}
            </div>
          </Card>
        </div>
      </div>

      {editService && (
        <Modal title={`Edit ${editService.name}`} onClose={() => setEditService(null)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setEditService(null)}>Cancel</button>
              <button type="button" className="btn btn--primary" disabled={saveService.isPending}
                onClick={() => saveService.mutate()}>Save changes</button>
            </>
          }>
          <div className="stack">
            <Field label="Name" htmlFor="svc-name">
              <input id="svc-name" value={name} onChange={(event) => setName(event.target.value)} />
            </Field>
            <Field label="Division" htmlFor="svc-division">
              <input id="svc-division" value={division} onChange={(event) => setDivision(event.target.value)} />
            </Field>
            <Field label="Description" htmlFor="svc-description">
              <textarea id="svc-description" value={description}
                onChange={(event) => setDescription(event.target.value)} />
            </Field>
            <label className="row small" style={{ gap: 6 }}>
              <input type="checkbox" style={{ width: 16, height: 16 }} checked={active}
                onChange={(event) => setActive(event.target.checked)} /> Active for new leads
            </label>
          </div>
        </Modal>
      )}

      {editStage && (
        <Modal title={`Edit ${editStage.name}`} onClose={() => setEditStage(null)}
          footer={
            <>
              <button type="button" className="btn" onClick={() => setEditStage(null)}>Cancel</button>
              <button type="button" className="btn btn--primary" disabled={saveStage.isPending}
                onClick={() => saveStage.mutate()}>Save changes</button>
            </>
          }>
          <div className="stack">
            <Field label="Stage name" htmlFor="stage-name">
              <input id="stage-name" value={name} onChange={(event) => setName(event.target.value)} />
            </Field>
            <label className="row small" style={{ gap: 6 }}>
              <input type="checkbox" style={{ width: 16, height: 16 }} checked={allowSkip}
                onChange={(event) => setAllowSkip(event.target.checked)} />
              Allow forward skip when destination gate evidence exists
            </label>
            <label className="row small" style={{ gap: 6 }}>
              <input type="checkbox" style={{ width: 16, height: 16 }} checked={active}
                disabled={editStage.terminalType !== "NONE"}
                onChange={(event) => setActive(event.target.checked)} />
              Active (terminal stages cannot be deactivated)
            </label>
          </div>
        </Modal>
      )}
    </>
  );
}
