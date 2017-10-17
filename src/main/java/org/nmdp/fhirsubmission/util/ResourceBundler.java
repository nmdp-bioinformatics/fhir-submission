package org.nmdp.fhirsubmission.util;

/**
 * Created by Andrew S. Brown, Ph.D., <andrew@nmdp.org>, on 10/11/17.
 * <p>
 * fhir-submission
 * Copyright (c) 2012-2017 National Marrow Donor Program (NMDP)
 * <p>
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 3 of the License, or (at
 * your option) any later version.
 * <p>
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; with out even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public
 * License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library;  if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307  USA.
 * <p>
 * > http://www.fsf.org/licensing/licenses/lgpl.html
 * > http://www.opensource.org/licenses/lgpl-license.php
 */

import com.google.gson.*;

import org.apache.log4j.Logger;
import org.nmdp.fhirsubmission.object.BundleSubmission;
import org.nmdp.fhirsubmission.serialization.DiagnosticReportJsonSerializer;
import org.nmdp.fhirsubmission.serialization.ObservationJsonSerializer;
import org.nmdp.fhirsubmission.serialization.PatientJsonSerializer;
import org.nmdp.fhirsubmission.serialization.SpecimenJsonSerializer;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.FhirMessage;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.Observation;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.Patient;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.Specimen;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.lists.Observations;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.lists.Patients;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.lists.Specimens;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

public class ResourceBundler {

    private static final String RESOURCE_TYPE_KEY = "resourceType";
    private static final String RESOURCE_TYPE_VALUE = "Bundle";
    private static final String BUNDLE_TYPE_KEY = "type";
    private static final String BUNDLE_TYPE_VALUE = "collection";
    //private static final String BUNDLE_TYPE_VALUE = "transaction";
    private static final String REQUEST_METHOD_KEY = "method";
    private static final String REQUEST_METOHD_VALUE = "POST";
    private static final String REQUEST_URL_KEY = "url";
    private static final String REQUEST_KEY = "request";
    private static final String SUBJECT_KEY = "subject";
    private static final String SPECIMEN_KEY = "specimen";
    private static final String REFERENCE_KEY = "reference";
    private static final String ENTRY = "entry";
    private static final String RESOURCE = "resource";
    private static final String FULL_URL = "fullUrl";
    private static final String GUID_PREFIX = "urn:uuid:";
    private static final String PATIENT_RESOURCE = "Patient";
    private static final String SPECIMEN_RESOURCE = "Specimen";
    private static final String OBSERVATION_RESOURCE = "Observation";
    private static final String DIAGNOSTIC_REPORT_RESOURCE = "DiagnosticReport";

    private static final Logger LOG = Logger.getLogger(ResourceBundler.class);

    public JsonArray serialize(FhirMessage fhir) {
        JsonArray patientBundle = new JsonArray();
        Patients patients = fhir.getPatients();

        for (Patient patient : patients.getPatients()) {
            BundleSubmission bundle = new BundleSubmission();
            ExecutorService executorService = Executors.newFixedThreadPool(6);
            bundle.setPatient(serializeToJsonSingleton(
                    getConverter(Patient.class, new PatientJsonSerializer()), patient, executorService));
            Specimens specimens = patient.getSpecimens();

            for (Specimen specimen : specimens.getSpecimens()) {
                String specimenId = String.format("%s%s", GUID_PREFIX, UUID.randomUUID().toString());
                bundle.addSpecimen(specimenId, serializeToJsonSingleton(getConverter(Specimen.class, new SpecimenJsonSerializer()),
                        specimen, executorService));
                bundle.addDiagnosticReport(specimenId, serializeToJsonSingleton(getConverter(Specimen.class, new DiagnosticReportJsonSerializer()),
                        specimen, executorService));
                Observations observations = specimen.getObservations();
                for (Observation observation : observations.getObservations()) {
                    bundle.addObservation(specimenId, serializeToJsonSingleton(getConverter(Observation.class, new ObservationJsonSerializer()),
                            observation, executorService));
                }
            }

            patientBundle.add(combine(bundle));
        }

        return patientBundle;
    }

    private String serializeToJsonSingleton(Gson gson, Object obj, ExecutorService executor) {
        try {
            Callable<String> callable = deserialize(obj, gson);
            Future<String> json = executor.submit(callable);
            return json.get();
        } catch (InterruptedException ex) {
            LOG.error(ex);
        } catch (ExecutionException ex) {
            LOG.error(ex);
        }

        return null;
    }

    private JsonObject combine(BundleSubmission bundleSubmission) {
        JsonObject bundle = new JsonObject();
        JsonArray entry = new JsonArray();
        Gson gson = new GsonBuilder().create();
        String patientId = String.format("%s%s", GUID_PREFIX, UUID.randomUUID().toString());

        handleBundle(bundleSubmission, gson, patientId, entry);
        bundle.addProperty(RESOURCE_TYPE_KEY, RESOURCE_TYPE_VALUE);
        bundle.addProperty(BUNDLE_TYPE_KEY, BUNDLE_TYPE_VALUE);
        bundle.add(ENTRY, entry);

        return bundle;
    }

    private void handleBundle(BundleSubmission bundle, Gson gson, String patientId, JsonArray entry) {
        for (Map.Entry<String, String> specimen : bundle.getSpecimens().entrySet()) {
            String specimenId = specimen.getKey();
            entry.add(createJsonObject(bundle.getPatient(), gson, PATIENT_RESOURCE, patientId, null, null));
            entry.add(createJsonObject(specimen.getValue(), gson, SPECIMEN_RESOURCE, specimenId, patientId, null));
            String diagnosticReportId = String.format("%s%s", GUID_PREFIX, UUID.randomUUID().toString());
            String diagnosticReport = bundle.getDiangosticReports().getOrDefault(specimenId, null);
            entry.add(createJsonObject(diagnosticReport, gson, DIAGNOSTIC_REPORT_RESOURCE, diagnosticReportId, patientId, specimenId));
            List<String> observations = bundle.getObservations().getOrDefault(specimenId, new ArrayList<>());
            for (String observation : observations) {
                String observationId = String.format("%s%s", GUID_PREFIX, UUID.randomUUID().toString());
                entry.add(createJsonObject(observation, gson, OBSERVATION_RESOURCE, observationId, specimenId, null));
            }
        }
    }

    private JsonObject createJsonObject(String str, Gson gson, String resource, String id, String refId, String specimenId) {
        JsonObject json = new JsonObject();
        JsonObject request = new JsonObject();

        if (str == null) {
            return json;
        }

        json.add(RESOURCE, gson.toJsonTree(str));
        json.addProperty(FULL_URL, id);
        request.addProperty(REQUEST_METHOD_KEY, REQUEST_METOHD_VALUE);
        request.addProperty(REQUEST_URL_KEY, resource);

        if (refId != null) {
            json = addReferenceToObject(refId, json);
        }

        if (specimenId != null) {
            JsonObject specimen = new JsonObject();
            specimen.addProperty(REFERENCE_KEY, specimenId);
            json.add(SPECIMEN_KEY, specimen);
        }

        json.add(REQUEST_KEY, request);

        return json;
    }

    private JsonObject addReferenceToObject(String refId, JsonObject json) {
        JsonObject subjectJson = new JsonObject();

        subjectJson.addProperty(REFERENCE_KEY, refId);
        json.remove(SUBJECT_KEY);
        json.add(SUBJECT_KEY, subjectJson);

        return json;
    }

    private Callable<String> deserialize(Object obj, Gson gson) {
        Callable<String> task = () -> {
            return gson.toJson(obj);
        };

        return task;
    }

    private <T> Gson getConverter(Class<T> clazz, JsonSerializer serializer) {
        GsonBuilder builder = new GsonBuilder();
        builder.registerTypeAdapter(clazz, serializer);
        return builder.create();
    }
}
