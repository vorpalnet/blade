Design Notes...

Bug - Schema Generation Crashes OCCAS

[[ACTIVE] ExecuteThread: '67' for queue: 'weblogic.kernel.Default (self-tuning)'] WARN com.kjetland.jackson.jsonSchema.SubclassesResolverImpl - Performance-warning. Since SubclassesResolver is not configured, it scans the entire classpath. https://github.com/mbknor/mbknor-jackson-jsonSchema#subclass-resolving-using-reflection


    final SubclassesResolver resolver = new SubclassesResolverImpl()
                                            .withPackagesToScan(Arrays.asList(
                                               "this.is.myPackage"
                                            ))
                                            .withClassesToScan(Arrays.asList( // and/or this one
                                               "this.is.myPackage.MyClass"
                                            ))
                                            //.withClassGraph() - or use this one to get full control..       
    
    config = config.withSubclassesResolver( resolver )