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

import org.apache.log4j.Logger;
import org.nmdp.fhirsubmission.exceptions.FhirBundleSubmissionFailException;
import org.nmdp.fhirsubmission.http.Post;
import org.nmdp.fhirsubmission.object.FhirSubmissionResponse;
import org.nmdp.fhirsubmission.serialization.DiagnosticReportJsonSerializer;
import org.nmdp.fhirsubmission.serialization.PatientJsonSerializer;
import org.nmdp.fhirsubmission.serialization.SpecimenJsonSerializer;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.FhirMessage;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.Patient;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.Specimen;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class FhirMessageUtil {

    private static final String URL = "http://fhirtest.b12x.org/baseDstu3/";
    private static final String QUERY_STRING = "?_format=json&_pretty=true&_summary=true";

    private static final String PATIENT = "Patient";
    private static final String SPECIMEN = "Specimen";
    private static final String DIAGNOSTIC_REPORT = "DiagnosticReport";

    private static final PatientJsonSerializer PATIENT_SERIALIZER = new PatientJsonSerializer();
    private static final SpecimenJsonSerializer SPECIMEN_SERIALIZER = new SpecimenJsonSerializer();
    private static final DiagnosticReportJsonSerializer DIAGNOSTIC_REPORT_SERIALIZER = new DiagnosticReportJsonSerializer();

    private static final Logger LOG = Logger.getLogger(FhirMessageUtil.class);

    public void submit(FhirMessage fhirMessage) throws Exception {
        List<Patient> patients = getPrimaryResources(fhirMessage);
        patients.forEach(patient -> submitPatientTree(patient));
    }

    private void submitPatientTree(Patient patient) {
        final String patientUrl = URL + PATIENT + QUERY_STRING;

        try {
            FhirSubmissionResponse response = HttpResponseExtractor
                    .parse(Post.post(patient, patientUrl, PATIENT_SERIALIZER, Patient.class));
            List<Specimen> specimens = patient.getSpecimens().getSpecimens();
            specimens.forEach(specimen -> specimen.setSubject(response));
            specimens.forEach(specimen -> submitSpecimenTree(specimen));
        } catch (FhirBundleSubmissionFailException ex) {
            LOG.error(ex);
        }
    }

    private void submitSpecimenTree(Specimen specimen) {
        final String specimenUrl = URL + SPECIMEN + QUERY_STRING;

        try {
            FhirSubmissionResponse response = HttpResponseExtractor
                    .parse(Post.post(specimen, specimenUrl, SPECIMEN_SERIALIZER, Specimen.class));
            specimen.setReference(response);
            submitDiagnosticReportTree(specimen);
        } catch (FhirBundleSubmissionFailException ex) {
            LOG.error(ex);
        }
    }

    private void submitDiagnosticReportTree(Specimen specimen) {
        final String diagnosticReportUrl = URL + DIAGNOSTIC_REPORT + QUERY_STRING;

        try {
            FhirSubmissionResponse response = HttpResponseExtractor
                    .parse(Post.post(specimen, diagnosticReportUrl, DIAGNOSTIC_REPORT_SERIALIZER, Specimen.class));
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
}
