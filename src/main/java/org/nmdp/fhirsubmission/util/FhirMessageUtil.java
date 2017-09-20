package org.nmdp.fhirsubmission.util;

/**
 * Created by Andrew S. Brown, Ph.D., <andrew@nmdp.org>, on 7/10/17.
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

import org.apache.http.HttpResponse;
import org.apache.log4j.Logger;
import org.nmdp.fhirsubmission.FhirSubmission;
import org.nmdp.fhirsubmission.exceptions.FhirBundleSubmissionFailException;
import org.nmdp.fhirsubmission.http.Post;
import org.nmdp.fhirsubmission.object.FhirSubmissionResponse;
import org.nmdp.fhirsubmission.serialization.DiagnosticReportJsonSerializer;
import org.nmdp.fhirsubmission.serialization.ObservationJsonSerializer;
import org.nmdp.fhirsubmission.serialization.PatientJsonSerializer;
import org.nmdp.fhirsubmission.serialization.SpecimenJsonSerializer;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.*;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.lists.Glstrings;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.lists.Observations;
import org.nmdp.hmlfhirmongo.models.DiagnosticReport;
import org.nmdp.hmlfhirmongo.models.Status;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

public class FhirMessageUtil {

    private static final String URL = "http://fhirtest.b12x.org/baseDstu3/";
    private static final String QUERY_STRING = "?_format=json&_pretty=true&_summary=true";

    private static final String PATIENT = "Patient";
    private static final String SPECIMEN = "Specimen";
    private static final String DIAGNOSTIC_REPORT = "DiagnosticReport";
    private static final String OBSERVATION = "Observation";

    private static final String ID_SEPARATOR = "_";
    private static final String SPLIT_CHAR_GL_STRING = "\\*";

    private static final PatientJsonSerializer PATIENT_SERIALIZER = new PatientJsonSerializer();
    private static final SpecimenJsonSerializer SPECIMEN_SERIALIZER = new SpecimenJsonSerializer();
    private static final DiagnosticReportJsonSerializer DIAGNOSTIC_REPORT_SERIALIZER = new DiagnosticReportJsonSerializer();
    private static final ObservationJsonSerializer OBSERVATION_SERIALIZER = new ObservationJsonSerializer();

    private static final Logger LOG = Logger.getLogger(FhirMessageUtil.class);

    public org.nmdp.hmlfhirmongo.models.FhirSubmission submit(FhirMessage fhirMessage) throws Exception {
        org.nmdp.hmlfhirmongo.models.FhirSubmission fhirSubmission = new org.nmdp.hmlfhirmongo.models.FhirSubmission();
        List<Patient> patients = getPrimaryResources(fhirMessage);
        patients.forEach(patient -> submitPatientTree(patient, fhirSubmission));

        return fhirSubmission;
    }

    private org.nmdp.hmlfhirmongo.models.FhirSubmission submitPatientTree(Patient patient, org.nmdp.hmlfhirmongo.models.FhirSubmission fhirSubmission) {
        final String patientUrl = URL + PATIENT + QUERY_STRING;

        try {
            FhirSubmissionResponse response = HttpResponseExtractor
                    .parse(Post.post(patient, patientUrl, PATIENT_SERIALIZER, Patient.class));
            List<Specimen> specimens = patient.getSpecimens().getSpecimens();
            specimens.forEach(specimen -> specimen.setSubject(response));
            specimens.forEach(specimen -> submitSpecimenTree(specimen, response, fhirSubmission));
        } catch (FhirBundleSubmissionFailException ex) {
            LOG.error(ex);
        } finally {
            return fhirSubmission;
        }
    }

    private void submitSpecimenTree(Specimen specimen, FhirSubmissionResponse patientResponse, org.nmdp.hmlfhirmongo.models.FhirSubmission fhirSubmission) {
        final String specimenUrl = URL + SPECIMEN + QUERY_STRING;

        try {
            FhirSubmissionResponse response = HttpResponseExtractor
                    .parse(Post.post(specimen, specimenUrl, SPECIMEN_SERIALIZER, Specimen.class));
            specimen.setReference(response);
            Map<String, FhirSubmissionResponse> observations = submitObservationTree(specimen, patientResponse);
            mapObservationResponsesToSpecimen(specimen, observations);
            submitDiagnosticReportTree(specimen, fhirSubmission);
        } catch (FhirBundleSubmissionFailException ex) {
            LOG.error(ex);
        }
    }

    private void mapObservationResponsesToSpecimen(Specimen specimen, Map<String, FhirSubmissionResponse> responses) {
        Observations specimenObservations = specimen.getObservations();

        for (Observation observation : specimenObservations.getObservations()) {
            Glstrings glstrings = observation.getGlstrings();
            Glstring glstring = glstrings.getGlstrings().stream().findFirst().get();
            String glAllele = pullGlStringAllele(glstring.getValue());
            FhirSubmissionResponse observationResponse = responses.get(glAllele);
            observation.setValue(observationResponse);
        }
    }

    private Map<String, FhirSubmissionResponse> submitObservationTree(Specimen specimen, FhirSubmissionResponse specimenResponse) {
        Observations observations = specimen.getObservations();
        Map<String, FhirSubmissionResponse> observationResponses = new HashMap<>();

        final String observationUrl = URL + OBSERVATION + QUERY_STRING;

        try {
            for (Observation observation : observations.getObservations()) {
                observation.setReference(specimenResponse);
                Glstrings glstrings = observation.getGlstrings();
                Glstring glstring = glstrings.getGlstrings().stream().findFirst().get();
                String genotype = pullGlStringAllele(glstring.getValue());
                FhirSubmissionResponse response = HttpResponseExtractor
                        .parse(Post.post(observation, observationUrl, OBSERVATION_SERIALIZER, Observation.class));

                observationResponses.put(genotype, response);
            }
        } catch (FhirBundleSubmissionFailException ex) {
            LOG.error(ex);
        } finally {
            return observationResponses;
        }
    }

    private void submitDiagnosticReportTree(Specimen specimen, org.nmdp.hmlfhirmongo.models.FhirSubmission fhirSubmission) {
        final String diagnosticReportUrl = URL + DIAGNOSTIC_REPORT + QUERY_STRING;
        String id = specimen.getIdentifier().getSystem() + ID_SEPARATOR + specimen.getIdentifier().getValue();

        try {
            HttpResponse httpResponse = Post.post(specimen, diagnosticReportUrl, DIAGNOSTIC_REPORT_SERIALIZER, Specimen.class);
            FhirSubmissionResponse response = HttpResponseExtractor.parse(httpResponse);
            DiagnosticReport report = new DiagnosticReport();

            Status status;

            switch (httpResponse.getStatusLine().getStatusCode()) {
                case 200:
                case 201:
                    status = Status.COMPLETE;
                    break;
                case 500:
                    status = Status.ERROR;
                    break;
                default:
                    status = Status.ERROR;
                    break;
            }

            report.setStatus(status);
            report.setResult(response.getUrl());
            fhirSubmission.addDiagnosticReport(id, report);
        } catch (FhirBundleSubmissionFailException ex) {
            LOG.error(ex);
        }
    }

    private List<Patient> getPrimaryResources(FhirMessage message) throws FhirBundleSubmissionFailException {

        return message.getPatients().getPatients();

//        for (Patient patient : message.getPatients().getPatients()) {
//            String patientJson = GSON.toJson(patient);
//            //List<Field> declaredFields = Arrays.asList(patient.getClass().getDeclaredFields());
//            List<Field> declaredFields = getAllFields(patient.getClass());
//            List<Object> fhirPrimaryResources = getFieldsImplementingAnnotation(FhirPrimaryResource.class, declaredFields);
//            List<Object> fhirResources = getFieldsImplementingAnnotation(FhirResource.class, declaredFields);

//            List<Object> fhirPrimaryResources = declaredFields.stream()
//                    .filter(Objects::nonNull)
//                    .map(field -> traverseObject(FhirPrimaryResource.class, field))
//                    .collect(Collectors.toList());
//
//            List<Object> fhirResources = declaredFields.stream()
//                    .filter(Objects::nonNull)
//                    .map(field -> traverseObject(FhirResource.class, field))
//                    .collect(Collectors.toList());
//        }
//
//        return Arrays.asList(new Object());
    }

    private List<Field> getAllFields(Class clazz) {
        List<Field> fields = new ArrayList<>();

        fields.addAll(Arrays.asList(clazz.getDeclaredFields()));

        if (clazz.getSuperclass() != null) {
            fields.addAll(getAllFields(clazz.getSuperclass()));
        }

        return fields;
    }

    private List<Object> traverseObject(Class<?> annotation, Object obj) {
       List<Object> resources = new ArrayList<>();
       List<Field> fields = Arrays.asList(obj.getClass().getDeclaredFields());

       for (Field field : fields) {
           try {
              field.setAccessible(true);
              resources.addAll(getFieldsImplementingAnnotation(annotation, field.get(obj)));
              resources.addAll(traverseObject(annotation, field.get(obj)));
           } catch (IllegalAccessException ex) {

           }
       }

       return resources;
    }

    private <T> T getFlatResource(T resource) {
        return resource;
    }

    private List<Object> getFieldsImplementingAnnotation(Class<?> annotation, List<Field> fields) {
        return fields.stream()
                .filter(Objects::nonNull)
                .filter(field -> Arrays.asList(field.getDeclaredAnnotations()).stream()
                    .filter(Objects::nonNull)
                    .anyMatch(ant -> ant.getClass().equals(annotation)))
                .collect(Collectors.toList());
    }

    private List<Object> getFieldsImplementingAnnotation(Class<?> annotation, Object obj) {
       return Arrays.asList(obj.getClass().getDeclaredFields()).stream()
               .filter(Objects::nonNull)
               .filter(field -> Arrays.asList(field.getDeclaredAnnotations()).stream()
                       .filter(Objects::nonNull)
                       .anyMatch(ant -> ant.getClass().equals(annotation)))
               .collect(Collectors.toList());
    }

    private String pullGlStringAllele(String allele) {
        String[] parts = allele.split(SPLIT_CHAR_GL_STRING);
        return parts[0];
    }
}
