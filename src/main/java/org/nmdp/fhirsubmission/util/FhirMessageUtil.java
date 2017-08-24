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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.nmdp.fhirsubmission.exceptions.FhirBundleSubmissionFailException;
import org.nmdp.fhirsubmission.http.Post;
import org.nmdp.fhirsubmission.serialization.PatientJsonSerializer;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.FhirMessage;
import org.nmdp.hmlfhirconvertermodels.domain.fhir.Patient;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class FhirMessageUtil {

    public void submit(FhirMessage fhirMessage) throws Exception {
        List<Patient> patients = getPrimaryResources(fhirMessage);


        patients.forEach(patient -> Post.post(getPatientJson(patient), "http://fhirtest.b12x.org/baseDstu3/Patient?_format=json&_pretty=true&_summary=true"));
    }

    private String getPatientJson(Patient patient) {
        PatientJsonSerializer serializer = new PatientJsonSerializer();
        GsonBuilder gsonBuilder = new GsonBuilder();
        gsonBuilder.registerTypeAdapter(Patient.class, serializer);
        Gson gson = gsonBuilder.create();
        return gson.toJson(patient);
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
