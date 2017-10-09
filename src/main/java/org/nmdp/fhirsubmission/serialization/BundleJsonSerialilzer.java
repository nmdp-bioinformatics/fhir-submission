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
import org.nmdp.hmlfhirconvertermodels.domain.fhir.*;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.lists.Patients;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.lists.Specimens;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

public class BundleJsonSerialilzer implements JsonSerializer<FhirMessage> {

    private static String RESOURCE_TYPE_KEY = "resourceType";
    private static String RESOURCE_TYPE_VALUE = "Bundle";
    private static String BUNDLE_TYPE_KEY = "type";
    private static String BUNDLE_TYPE_VALUE = "collection";
    private static String ENTRY = "entry";
    private static String RESOURCE = "resource";
    private static String FULL_URL = "fullUrl";
    private static String GUID_PREFIX = "urn:uuid:";

    private static final Logger LOG = Logger.getLogger(BundleJsonSerialilzer.class);

    @Override
    public JsonElement serialize(FhirMessage fhir, Type typeOfSource, JsonSerializationContext context) {
        JsonArray patientBundle = new JsonArray();
        Patients patients = fhir.getPatients();
        Gson patientJsonConverter = getConverter(Patient.class, new PatientJsonSerializer());

        for (Patient patient : patients.getPatients()) {
            ExecutorService executorService = Executors.newFixedThreadPool(6);
            String patientJson = patientJsonConverter.toJson(patient);
            Specimens specimens = patient.getSpecimens();

            List<String> specimensJson = serializeToJson(getConverter(Specimen.class, new SpecimenJsonSerializer()), specimens.getSpecimens(), executorService);
            List<String> diagnosticReportsJson = serializeToJson(getConverter(DiagnosticReport.class, new DiagnosticReportJsonSerializer()), specimens.getSpecimens(), executorService);
            List<String> observationsJson = serializeToJson(getConverter(Observation.class, new ObservationJsonSerializer()), specimens.getSpecimens(), executorService);

            patientBundle.add(combine(patientJson, specimensJson, observationsJson, diagnosticReportsJson));
        }

        return patientBundle;
    }

    private List<String> serializeToJson(Gson gson, List<Specimen> specimens, ExecutorService executor) {
        List<String> json = new ArrayList<>();

        try {
            for (Specimen specimen : specimens) {
                Callable<String> callable = deserialize(specimen, gson);
                Future<String> specimenJson = executor.submit(callable);
                json.add(specimenJson.get());
            }
        } catch (InterruptedException ex) {
             LOG.error(ex);
        } catch (ExecutionException ex) {
            LOG.error(ex);
        } finally {
            return json;
        }
    }

    private JsonObject combine(String patientJson, List<String> specimenJson, List<String> observationJson,
                               List<String> diagnosticReportJson) {
        JsonObject bundle = new JsonObject();
        JsonArray entry = new JsonArray();
        Gson gson = new GsonBuilder().create();
        String id = String.format("%s%s", GUID_PREFIX, UUID.randomUUID().toString());

        entry.add(createJsonObject(patientJson, gson, id));

        specimenJson.forEach(specimen -> entry.add(createJsonObject(specimen, gson, id)));
        observationJson.forEach(observation -> entry.add(createJsonObject(observation, gson, id)));
        diagnosticReportJson.forEach(diagnosticReport -> entry.add(createJsonObject(diagnosticReport, gson, id)));

        bundle.addProperty(RESOURCE_TYPE_KEY, RESOURCE_TYPE_VALUE);
        bundle.addProperty(BUNDLE_TYPE_KEY, BUNDLE_TYPE_VALUE);
        bundle.add(ENTRY, entry);

        return bundle;
    }

    private JsonObject createJsonObject(String str, Gson gson, String id) {
        JsonObject json = new JsonObject();
        json.add(RESOURCE, gson.toJsonTree(str));
        json.addProperty(FULL_URL, id);

        return json;
    }

    private Callable<String> deserialize(Specimen specimen, Gson gson) {
        Callable<String> task = () -> {
              return gson.toJson(specimen);
        };

        return task;
    }

    private <T> Gson getConverter(Class<T> clazz, JsonSerializer serializer) {
        GsonBuilder builder = new GsonBuilder();
        builder.registerTypeAdapter(clazz, serializer);
        return builder.create();
    }
}
