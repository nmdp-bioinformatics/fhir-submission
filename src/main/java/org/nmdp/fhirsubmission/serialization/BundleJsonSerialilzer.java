package org.nmdp.fhirsubmission.serialization;

/**
 * Created by Andrew S. Brown, Ph.D., <andrew@nmdp.org>, on 10/9/17.
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
import org.nmdp.hmlfhirconvertermodels.domain.fhir.*;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.lists.Observations;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.lists.Patients;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.lists.Specimens;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

public class BundleJsonSerialilzer implements JsonSerializer<FhirMessage> {

    private static String RESOURCE_TYPE_KEY = "resourceType";
    private static String RESOURCE_TYPE_VALUE = "Bundle";
    private static String BUNDLE_TYPE_KEY = "type";
    private static String BUNDLE_TYPE_VALUE = "collection";
    private static String SUBJECT_KEY = "subject";
    private static String REFERENCE_KEY = "reference";
    private static String ENTRY = "entry";
    private static String RESOURCE = "resource";
    private static String FULL_URL = "fullUrl";
    private static String GUID_PREFIX = "urn:uuid:";

    private static final Logger LOG = Logger.getLogger(BundleJsonSerialilzer.class);

    @Override
    public JsonElement serialize(FhirMessage fhir, Type typeOfSource, JsonSerializationContext context) {
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
            Future<String> patientJson = executor.submit(callable);
            return patientJson.get();
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
            entry.add(createJsonObject(specimen.getValue(), gson, specimenId, patientId));
            String diagnosticReportId = String.format("%s%s", GUID_PREFIX, UUID.randomUUID().toString());
            String diagnosticReport = bundle.getDiangosticReports().getOrDefault(specimenId, null);
            entry.add(createJsonObject(diagnosticReport, gson, diagnosticReportId, specimenId));
            List<String> observations = bundle.getObservations().getOrDefault(specimenId, new ArrayList<>());
            for (String observation : observations) {
                String observationId = String.format("%s%s", GUID_PREFIX, UUID.randomUUID().toString());
                entry.add(createJsonObject(observation, gson, observationId, specimenId));
            }
        }
    }

    private JsonObject createJsonObject(String str, Gson gson, String id, String refId) {
        JsonObject json = new JsonObject();

        if (str == null) {
            return json;
        }

        json.add(RESOURCE, gson.toJsonTree(str));
        json.addProperty(FULL_URL, id);

        if (refId != null) {
            json = addReferenceToObject(refId, json);
        }

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
