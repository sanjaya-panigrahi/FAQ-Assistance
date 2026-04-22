{{/*
Expand the name of the chart.
*/}}
{{- define "spring-ai-faq-retrieval.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "spring-ai-faq-retrieval.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "spring-ai-faq-retrieval.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "spring-ai-faq-retrieval.labels" -}}
helm.sh/chart: {{ include "spring-ai-faq-retrieval.chart" . }}
{{ include "spring-ai-faq-retrieval.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "spring-ai-faq-retrieval.selectorLabels" -}}
app.kubernetes.io/name: {{ include "spring-ai-faq-retrieval.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app: spring-ai-faq-retrieval
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "spring-ai-faq-retrieval.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "spring-ai-faq-retrieval.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Pod annotations
*/}}
{{- define "spring-ai-faq-retrieval.podAnnotations" -}}
{{- with .Values.podAnnotations }}
{{- toYaml . }}
{{- end }}
{{- end }}
