/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.container.test.impl.client.deployment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.jboss.arquillian.container.spi.Container;
import org.jboss.arquillian.container.spi.ContainerRegistry;
import org.jboss.arquillian.container.spi.client.deployment.Deployment;
import org.jboss.arquillian.container.spi.client.deployment.DeploymentDescription;
import org.jboss.arquillian.container.spi.client.deployment.DeploymentScenario;
import org.jboss.arquillian.container.spi.client.deployment.TargetDescription;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.container.test.impl.client.deployment.event.GenerateDeployment;
import org.jboss.arquillian.container.test.impl.domain.ProtocolDefinition;
import org.jboss.arquillian.container.test.impl.domain.ProtocolRegistry;
import org.jboss.arquillian.container.test.spi.TestDeployment;
import org.jboss.arquillian.container.test.spi.client.deployment.ApplicationArchiveProcessor;
import org.jboss.arquillian.container.test.spi.client.deployment.AuxiliaryArchiveAppender;
import org.jboss.arquillian.container.test.spi.client.deployment.AuxiliaryArchiveProcessor;
import org.jboss.arquillian.container.test.spi.client.deployment.DeploymentPackager;
import org.jboss.arquillian.container.test.spi.client.deployment.DeploymentScenarioGenerator;
import org.jboss.arquillian.container.test.spi.client.deployment.ProtocolArchiveProcessor;
import org.jboss.arquillian.container.test.spi.client.protocol.Protocol;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.core.spi.ServiceLoader;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.arquillian.test.spi.annotation.ClassScoped;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.container.ClassContainer;

/**
 * DeploymentGenerator
 *
 * @author <a href="mailto:aslak@redhat.com">Aslak Knutsen</a>
 * @version $Revision: $
 */
public class DeploymentGenerator
{
   @Inject
   private Instance<ServiceLoader> serviceLoader;
   
   @Inject @ClassScoped
   private InstanceProducer<DeploymentScenario> deployment;

   @Inject
   private Instance<ContainerRegistry> containerRegistry;
   
   @Inject
   private Instance<ProtocolRegistry> protocolRegistry;

   public void generateDeployment(@Observes GenerateDeployment event)
   {
      DeploymentScenarioGenerator generator = serviceLoader.get().onlyOne(
            DeploymentScenarioGenerator.class, AnnotationDeploymentScenarioGenerator.class);
      
      DeploymentScenario scenario = new DeploymentScenario();
      
      for(DeploymentDescription deployment : generator.generate(event.getTestClass())) 
      {
         scenario.addDeployment(deployment);
      }

      validate(scenario);
      createTestableDeployments(scenario, event.getTestClass());

      deployment.set(scenario);
   }

   //-------------------------------------------------------------------------------------||
   // Validate DeploymentScenario --------------------------------------------------------||
   //-------------------------------------------------------------------------------------||

   protected void validate(DeploymentScenario scenario)
   {
      ContainerRegistry conReg = containerRegistry.get();
      for(TargetDescription target : scenario.targets())
      {
         Container container = conReg.getContainer(target);
         if(container == null)
         {
            throw new ValidationException(
                  DeploymentScenario.class.getSimpleName() + " contains targets not matching any defined Container in the registry. " + target.getName() + 
                  ". Possible causes are: No Deployable Container found on Classpath or " + 
                  "your have defined a @" + org.jboss.arquillian.container.test.api.Deployment.class.getName() + " with a @" + TargetsContainer.class.getName() + 
                  " value that does not match any found/configured Containers (see arquillian.xml container@qualifier) ");
         }
      }
      
     ProtocolRegistry proReg = protocolRegistry.get();
     for(ProtocolDescription proDesc : scenario.protocols())
     {
        if(ProtocolDescription.DEFAULT.equals(proDesc))
        {
           continue;
        }
        ProtocolDefinition protocol = proReg.getProtocol(proDesc);
        if(protocol == null)
        {
           throw new ValidationException(
                 DeploymentScenario.class.getSimpleName() + " contains protocols not maching any defined Protocol in the registry. " + proDesc.getName());
        }
     }
   }

   //-------------------------------------------------------------------------------------||
   // Enrich with Protocol Packaging -----------------------------------------------------||
   //-------------------------------------------------------------------------------------||

   /**
    * @param scenario
    */
   private void createTestableDeployments(DeploymentScenario scenario, TestClass testCase)
   {
      ProtocolRegistry protoReg = protocolRegistry.get();
      buildTestableDeployments(scenario, testCase, protoReg);
   }

   private void buildTestableDeployments(DeploymentScenario scenario, TestClass testCase, ProtocolRegistry protoReg)
   {
      for(Deployment deployment : scenario.deployments())
      {
         DeploymentDescription description = deployment.getDescription();
         if(!description.testable() || !description.isArchiveDeployment())
         {
            continue;
         }
         // TODO: could be optimalized to only be loaded pr Container
         List<Archive<?>> auxiliaryArchives = loadAuxiliaryArchives(description);
         
         ProtocolDefinition protocolDefinition = protoReg.getProtocol(description.getProtocol());
         if(protocolDefinition == null)
         {
            protocolDefinition = protoReg.getProtocol(
                  containerRegistry.get().getContainer(description.getTarget()).getDeployableContainer().getDefaultProtocol());
         }
         Protocol<?> protocol = protocolDefinition.getProtocol();
         DeploymentPackager packager = protocol.getPackager();

         Archive<?> applicationArchive = description.getArchive();
         applyApplicationProcessors(description.getArchive(), testCase);
         applyAuxiliaryProcessors(auxiliaryArchives);

         try
         {
            // this should be made more reliable, does not work with e.g. a EnterpriseArchive
            if(ClassContainer.class.isInstance(applicationArchive)) 
            {
               ClassContainer<?> classContainer = ClassContainer.class.cast(applicationArchive);
               classContainer.addClass(testCase.getJavaClass());
            }
         } 
         catch (UnsupportedOperationException e) 
         { 
            /*
             * Quick Fix: https://jira.jboss.org/jira/browse/ARQ-118
             * Keep in mind when rewriting for https://jira.jboss.org/jira/browse/ARQ-94
             * that a ShrinkWrap archive might not support a Container if even tho the 
             * ContianerBase implements it. Check the Archive Interface..  
             */
         }
         description.setTestableArchive(
               packager.generateDeployment(
                     new TestDeployment(deployment.getDescription(), applicationArchive, auxiliaryArchives),
                     serviceLoader.get().all(ProtocolArchiveProcessor.class)));
      }
   }

   private List<Archive<?>> loadAuxiliaryArchives(DeploymentDescription deployment) 
   {
      List<Archive<?>> archives = new ArrayList<Archive<?>>();

      // load based on the Containers ClassLoader
      Collection<AuxiliaryArchiveAppender> archiveAppenders = serviceLoader.get().all(AuxiliaryArchiveAppender.class);
      
      for(AuxiliaryArchiveAppender archiveAppender : archiveAppenders)
      {
         archives.add(archiveAppender.createAuxiliaryArchive());
      }
      return archives;
   }

   private void applyApplicationProcessors(Archive<?> applicationArchive, TestClass testClass)
   {
      Collection<ApplicationArchiveProcessor> processors = serviceLoader.get().all(ApplicationArchiveProcessor.class);
      for(ApplicationArchiveProcessor processor : processors)
      {
         processor.process(applicationArchive, testClass);
      }
   }
   
   private void applyAuxiliaryProcessors(List<Archive<?>> auxiliaryArchives)
   {
      Collection<AuxiliaryArchiveProcessor> processors = serviceLoader.get().all(AuxiliaryArchiveProcessor.class);
      for(AuxiliaryArchiveProcessor processor : processors)
      {
         for(Archive<?> auxiliaryArchive : auxiliaryArchives)
         {
            processor.process(auxiliaryArchive);
         }
      }
   }
}
